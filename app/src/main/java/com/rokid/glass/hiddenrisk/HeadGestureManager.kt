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

/**
 * 头部动作识别管理器。
 * 基于 GAME_ROTATION_VECTOR + GYROSCOPE 做位移检测：
 * 每次脉冲结束后将当前姿态设为新的中性参考点，
 * 校验完全依赖单次动作的运动学特征（位移量、角速度双向性、对称性），
 * 与绝对朝向解耦。
 */
object HeadGestureManager : SensorEventListener {

    private const val TAG = "HeadGestureManager"
    private const val DEFAULT_SMOOTHING_ALPHA = 0.22f
    private const val SENSOR_LOG_INTERVAL_NS = 30_000_000L  // 30ms 密集输出用于调试

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

    /**
     * 配置参数。
     * 所有角度阈值为相对于脉冲起点的位移，不再依赖全局基线。
     */
    private data class Config(
        // 中性区阈值（相对当前中性参考点）
        val neutralEnterPitchDeg: Float = 5.5f,
        val neutralEnterYawDeg: Float = 5.5f,
        val neutralExitPitchDeg: Float = 7.0f,
        val neutralExitYawDeg: Float = 7.0f,
        val neutralStableDurationNs: Long = 220_000_000L,

        // 手势时间窗口
        val maxGestureWindowNs: Long = 1_000_000_000L,    // 放宽：回弹慢的用户需要更多时间
        val minGestureWindowNs: Long = 260_000_000L,
        val emitCooldownNs: Long = 450_000_000L,
        val pulseWindowNs: Long = 1_200_000_000L,         // 放宽：连续点头间隔可能较大

        // 点头校验
        val nodStartPitchDeg: Float = 6.0f,               // 降低：临界线附近经常被卡
        val nodStrongDeg: Float = 7.5f,                   // 总行程（max - min）
        val nodReturnFromStartDeg: Float = 15f,           // 放宽：用户回弹经常过冲
        val minNodTravelDeg: Float = 5.5f,                // 主方向位移
        val nodCrossAxisMaxDeg: Float = 11f,

        // 摇头校验
        val shakeStartYawDeg: Float = 7.5f,               // 降低：同点头
        val shakeStrongDeg: Float = 14f,                  // 总行程（max - min）
        val shakeReturnFromStartDeg: Float = 15f,         // 放宽：同点头
        val minShakeTravelDeg: Float = 12f,               // 主方向位移
        val shakeCrossAxisMaxDeg: Float = 11f,

        // 启动交叉轴约束
        val startCrossAxisMaxDeg: Float = 7f,             // 放宽：人头部运动天然有交叉轴耦合

        // 波形启动观察窗口（替代单帧阈值）
        val startupWindowNs: Long = 200_000_000L,         // 200ms 观察期
        val startupNodTravelDeg: Float = 4.0f,            // 观察期内 pitch 累计位移
        val startupShakeTravelDeg: Float = 5.0f,          // 观察期内 yaw 累计位移
        val startupCrossAxisMaxDeg: Float = 8f,           // 观察期交叉轴上限

        // 角速度阈值（收紧，过滤走路颠簸）
        val minPitchRateRad: Float = 0.9f,                // ≈ 52°/s
        val minYawRateRad: Float = 0.7f,                  // ≈ 40°/s
        val maxRateAsymmetry: Float = 0.6f,               // 正负峰值不对称度上限

        // 滤波
        val smoothingAlpha: Float = DEFAULT_SMOOTHING_ALPHA,

        // 日志
        val enableDecisionLogs: Boolean = true,
        val enableSensorLogs: Boolean = true,
    )

    private enum class PulseType {
        NOD_DOWN,
        SHAKE,
    }

    /**
     * 启动观察期候选：记录离开中性区后 200ms 内的波形数据。
     * 不依赖单帧阈值，而是评估一段运动轨迹的累计特征。
     */
    private data class StartupCandidate(
        val type: PulseType,
        val startTimestampNs: Long,
        val startPitchDeg: Float,
        val startYawDeg: Float,
        var maxPitchDeg: Float,
        var minPitchDeg: Float,
        var maxYawDeg: Float,
        var minYawDeg: Float,
        var maxGyroPitchRate: Float,
        var minGyroPitchRate: Float,
        var maxGyroYawRate: Float,
        var minGyroYawRate: Float,
    )

    private data class PulseCandidate(
        val type: PulseType,
        val startTimestampNs: Long,
        val startPitchDeg: Float,
        val startYawDeg: Float,
        var lastPitchDeg: Float,
        var lastYawDeg: Float,
        var maxPitchDeg: Float,
        var minPitchDeg: Float,
        var maxYawDeg: Float,
        var minYawDeg: Float,
        var maxGyroPitchRate: Float,
        var minGyroPitchRate: Float,
        var maxGyroYawRate: Float,
        var minGyroYawRate: Float,
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

    // 传感器原始/滤波值
    private var latestGyroValues = FloatArray(3)
    private var latestPitchDeg = 0f
    private var latestYawDeg = 0f
    private var smoothedPitchDeg = 0f
    private var smoothedYawDeg = 0f
    private var hasSmoothedPose = false

    // 中性参考点：每次脉冲结束后更新到当前姿态
    private var neutralRefPitch = 0f
    private var neutralRefYaw = 0f

    // Yaw 解缠绕
    private var lastYawRad: Float? = null
    private var unwrappedYawRad = 0f

    // 中性区状态
    private var neutralStableSinceNs = 0L
    private var neutralLatched = false
    private var wasInNeutral = false

    // 脉冲检测
    private var activeCandidate: PulseCandidate? = null
    private var startupCandidate: StartupCandidate? = null
    private var lastEmitTimestampNs = 0L

    // 聚合
    private val nodPulseTimestamps = ArrayDeque<Long>()
    private val shakePulseTimestamps = ArrayDeque<Long>()

    // 日志节流
    private var lastSensorLogTimestampNs = 0L

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
        Log.i(
            TAG,
            "initialize supported=${isSupported()} rotationVector=${rotationVectorSensor?.name ?: "N/A"} gyro=${gyroscopeSensor?.name ?: "N/A"}",
        )
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
        } else {
            Log.i(TAG, "head gesture listening started")
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
        Log.i(TAG, "head gesture listening stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                latestGyroValues[0] = event.values.getOrElse(0) { 0f }
                latestGyroValues[1] = event.values.getOrElse(1) { 0f }
                latestGyroValues[2] = event.values.getOrElse(2) { 0f }
            }

            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                handleRotationVector(event)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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
            captureInitialNeutralRef()
        } else {
            smoothedPitchDeg = lowPass(smoothedPitchDeg, pitchDeg, config.smoothingAlpha)
            smoothedYawDeg = lowPass(smoothedYawDeg, yawDeg, config.smoothingAlpha)
        }

        val relativePitchDeg = smoothedPitchDeg - neutralRefPitch
        val relativeYawDeg = smoothedYawDeg - neutralRefYaw
        val inNeutral = computeNeutralState(relativePitchDeg, relativeYawDeg)
        updateNeutralState(event.timestamp, inNeutral)
        updatePulseDetection(
            timestampNs = event.timestamp,
            relativePitchDeg = relativePitchDeg,
            relativeYawDeg = relativeYawDeg,
            inNeutral = inNeutral,
        )
        maybeLogSensorSnapshot(event.timestamp, inNeutral)
    }

    private fun updateNeutralState(timestampNs: Long, inNeutral: Boolean) {
        if (!inNeutral) {
            if (config.enableDecisionLogs && neutralStableSinceNs != 0L) {
                Log.d(
                    TAG,
                    "neutral broken relPitch=${format1(smoothedPitchDeg - neutralRefPitch)} " +
                        "relYaw=${format1(smoothedYawDeg - neutralRefYaw)}",
                )
            }
            neutralStableSinceNs = 0L
            return
        }
        if (neutralStableSinceNs == 0L) {
            neutralStableSinceNs = timestampNs
            return
        }
        if (!neutralLatched && timestampNs - neutralStableSinceNs >= config.neutralStableDurationNs) {
            neutralLatched = true
            if (config.enableDecisionLogs) {
                Log.d(
                    TAG,
                    "neutral latched relPitch=${format1(smoothedPitchDeg - neutralRefPitch)} " +
                        "relYaw=${format1(smoothedYawDeg - neutralRefYaw)}",
                )
            }
        }
    }

    /**
     * 首次收到姿态数据时捕获初始中性参考点。
     */
    private fun captureInitialNeutralRef() {
        neutralRefPitch = smoothedPitchDeg
        neutralRefYaw = smoothedYawDeg
        neutralStableSinceNs = 0L
        neutralLatched = false
        if (config.enableDecisionLogs) {
            Log.d(
                TAG,
                "initial neutral ref captured pitch=${format1(neutralRefPitch)} yaw=${format1(neutralRefYaw)}",
            )
        }
    }

    /**
     * 脉冲结束后更新中性参考点到当前姿态。
     * 这样用户可以在任意头部位置自然地连续做手势。
     */
    private fun updateNeutralRefAfterPulse(timestampNs: Long) {
        neutralRefPitch = smoothedPitchDeg
        neutralRefYaw = smoothedYawDeg
        neutralStableSinceNs = timestampNs
        neutralLatched = true
        wasInNeutral = true
        if (config.enableDecisionLogs) {
            Log.d(
                TAG,
                "neutral ref updated after pulse pitch=${format1(neutralRefPitch)} yaw=${format1(neutralRefYaw)}",
            )
        }
    }

    private fun computeNeutralState(relativePitchDeg: Float, relativeYawDeg: Float): Boolean {
        val pitchAbs = abs(relativePitchDeg)
        val yawAbs = abs(relativeYawDeg)
        val inNeutral = if (wasInNeutral) {
            pitchAbs <= config.neutralExitPitchDeg && yawAbs <= config.neutralExitYawDeg
        } else {
            pitchAbs <= config.neutralEnterPitchDeg && yawAbs <= config.neutralEnterYawDeg
        }
        wasInNeutral = inNeutral
        return inNeutral
    }

    private fun updatePulseDetection(
        timestampNs: Long,
        relativePitchDeg: Float,
        relativeYawDeg: Float,
        inNeutral: Boolean,
    ) {
        updateActiveCandidate(timestampNs, relativePitchDeg, relativeYawDeg, inNeutral)
        updateStartupCandidate(timestampNs, relativePitchDeg, relativeYawDeg, inNeutral)

        if (activeCandidate == null && startupCandidate == null && !inNeutral) {
            // 允许两种情况启动：
            // 1. 中性区已锁定（头部之前在中性区稳定过）
            // 2. 检测到显著的角速度（表明是主动运动而非随机扰动）
            val hasActiveMotion = abs(latestGyroValues[0]) > 0.5f || abs(latestGyroValues[2]) > 0.5f
            if (neutralLatched || hasActiveMotion) {
                neutralLatched = false
                when {
                    shouldStartNod(relativePitchDeg, relativeYawDeg) -> {
                        startupCandidate = StartupCandidate(
                            type = PulseType.NOD_DOWN,
                            startTimestampNs = timestampNs,
                            startPitchDeg = relativePitchDeg,
                            startYawDeg = relativeYawDeg,
                            maxPitchDeg = relativePitchDeg,
                            minPitchDeg = relativePitchDeg,
                            maxYawDeg = relativeYawDeg,
                            minYawDeg = relativeYawDeg,
                            maxGyroPitchRate = latestGyroValues[0],
                            minGyroPitchRate = latestGyroValues[0],
                            maxGyroYawRate = latestGyroValues[2],
                            minGyroYawRate = latestGyroValues[2],
                        )
                        if (config.enableDecisionLogs) {
                            Log.d(
                                TAG,
                                "startup created type=NOD_DOWN relPitch=${format1(relativePitchDeg)} relYaw=${format1(relativeYawDeg)} " +
                                    "gyroPitch=${format2(latestGyroValues[0])} gyroYaw=${format2(latestGyroValues[2])}",
                            )
                        }
                    }

                    shouldStartShake(relativePitchDeg, relativeYawDeg) -> {
                        startupCandidate = StartupCandidate(
                            type = PulseType.SHAKE,
                            startTimestampNs = timestampNs,
                            startPitchDeg = relativePitchDeg,
                            startYawDeg = relativeYawDeg,
                            maxPitchDeg = relativePitchDeg,
                            minPitchDeg = relativePitchDeg,
                            maxYawDeg = relativeYawDeg,
                            minYawDeg = relativeYawDeg,
                            maxGyroPitchRate = latestGyroValues[0],
                            minGyroPitchRate = latestGyroValues[0],
                            maxGyroYawRate = latestGyroValues[2],
                            minGyroYawRate = latestGyroValues[2],
                        )
                        if (config.enableDecisionLogs) {
                            Log.d(
                                TAG,
                                "startup created type=SHAKE relPitch=${format1(relativePitchDeg)} relYaw=${format1(relativeYawDeg)} " +
                                    "gyroPitch=${format2(latestGyroValues[0])} gyroYaw=${format2(latestGyroValues[2])}",
                            )
                        }
                    }

                    else -> {
                        if (config.enableDecisionLogs) {
                            Log.d(
                                TAG,
                                "pulse start ignored relPitch=${format1(relativePitchDeg)} relYaw=${format1(relativeYawDeg)} " +
                                    "needNod(pitch>=${format1(config.nodStartPitchDeg)}, yaw<=${format1(config.startCrossAxisMaxDeg)}) " +
                                    "needShake(yaw>=${format1(config.shakeStartYawDeg)}, pitch<=${format1(config.startCrossAxisMaxDeg)})",
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 波形启动观察：在 startupWindowNs（200ms）内持续收集位移/角速度数据，
     * 动态评估是否满足启动条件，替代原来的单帧阈值判断。
     */
    private fun updateStartupCandidate(
        timestampNs: Long,
        relativePitchDeg: Float,
        relativeYawDeg: Float,
        inNeutral: Boolean,
    ) {
        val candidate = startupCandidate ?: return

        // 如果头部回到了中性区，提前放弃
        if (inNeutral) {
            if (config.enableDecisionLogs) {
                Log.d(
                    TAG,
                    "startup abandoned: returned to neutral type=${candidate.type} " +
                        "relPitch=${format1(relativePitchDeg)} relYaw=${format1(relativeYawDeg)}",
                )
            }
            startupCandidate = null
            return
        }

        // 更新波形数据
        candidate.maxPitchDeg = maxOf(candidate.maxPitchDeg, relativePitchDeg)
        candidate.minPitchDeg = minOf(candidate.minPitchDeg, relativePitchDeg)
        candidate.maxYawDeg = maxOf(candidate.maxYawDeg, relativeYawDeg)
        candidate.minYawDeg = minOf(candidate.minYawDeg, relativeYawDeg)
        candidate.maxGyroPitchRate = maxOf(candidate.maxGyroPitchRate, latestGyroValues[0])
        candidate.minGyroPitchRate = minOf(candidate.minGyroPitchRate, latestGyroValues[0])
        candidate.maxGyroYawRate = maxOf(candidate.maxGyroYawRate, latestGyroValues[2])
        candidate.minGyroYawRate = minOf(candidate.minGyroYawRate, latestGyroValues[2])

        // 动态评估：只要主方向位移够就立即升级为真正的 PulseCandidate
        when (candidate.type) {
            PulseType.NOD_DOWN -> {
                val pitchTravel = if (relativePitchDeg > candidate.startPitchDeg) {
                    candidate.maxPitchDeg - candidate.startPitchDeg
                } else {
                    candidate.startPitchDeg - candidate.minPitchDeg
                }
                val yawTravel = maxOf(
                    abs(candidate.maxYawDeg - candidate.startYawDeg),
                    abs(candidate.minYawDeg - candidate.startYawDeg),
                )

                if (pitchTravel >= config.startupNodTravelDeg &&
                    yawTravel <= config.startupCrossAxisMaxDeg
                ) {
                    activeCandidate = PulseCandidate(
                        type = PulseType.NOD_DOWN,
                        startTimestampNs = candidate.startTimestampNs,
                        startPitchDeg = candidate.startPitchDeg,
                        startYawDeg = candidate.startYawDeg,
                        lastPitchDeg = relativePitchDeg,
                        lastYawDeg = relativeYawDeg,
                        maxPitchDeg = candidate.maxPitchDeg,
                        minPitchDeg = candidate.minPitchDeg,
                        maxYawDeg = candidate.maxYawDeg,
                        minYawDeg = candidate.minYawDeg,
                        maxGyroPitchRate = candidate.maxGyroPitchRate,
                        minGyroPitchRate = candidate.minGyroPitchRate,
                        maxGyroYawRate = candidate.maxGyroYawRate,
                        minGyroYawRate = candidate.minGyroYawRate,
                    )
                    startupCandidate = null
                    if (config.enableDecisionLogs) {
                        Log.d(
                            TAG,
                            "startup promoted type=NOD_DOWN pitchTravel=${format1(pitchTravel)} " +
                                "yawTravel=${format1(yawTravel)} durationMs=${(timestampNs - candidate.startTimestampNs) / 1_000_000}",
                        )
                    }
                }
            }

            PulseType.SHAKE -> {
                val yawTravel = if (relativeYawDeg > candidate.startYawDeg) {
                    candidate.maxYawDeg - candidate.startYawDeg
                } else {
                    candidate.startYawDeg - candidate.minYawDeg
                }
                val pitchTravel = maxOf(
                    abs(candidate.maxPitchDeg - candidate.startPitchDeg),
                    abs(candidate.minPitchDeg - candidate.startPitchDeg),
                )

                if (yawTravel >= config.startupShakeTravelDeg &&
                    pitchTravel <= config.startupCrossAxisMaxDeg
                ) {
                    activeCandidate = PulseCandidate(
                        type = PulseType.SHAKE,
                        startTimestampNs = candidate.startTimestampNs,
                        startPitchDeg = candidate.startPitchDeg,
                        startYawDeg = candidate.startYawDeg,
                        lastPitchDeg = relativePitchDeg,
                        lastYawDeg = relativeYawDeg,
                        maxPitchDeg = candidate.maxPitchDeg,
                        minPitchDeg = candidate.minPitchDeg,
                        maxYawDeg = candidate.maxYawDeg,
                        minYawDeg = candidate.minYawDeg,
                        maxGyroPitchRate = candidate.maxGyroPitchRate,
                        minGyroPitchRate = candidate.minGyroPitchRate,
                        maxGyroYawRate = candidate.maxGyroYawRate,
                        minGyroYawRate = candidate.minGyroYawRate,
                    )
                    startupCandidate = null
                    if (config.enableDecisionLogs) {
                        Log.d(
                            TAG,
                            "startup promoted type=SHAKE yawTravel=${format1(yawTravel)} " +
                                "pitchTravel=${format1(pitchTravel)} durationMs=${(timestampNs - candidate.startTimestampNs) / 1_000_000}",
                        )
                    }
                }
            }
        }

        // 超时放弃
        if (timestampNs - candidate.startTimestampNs > config.startupWindowNs) {
            if (config.enableDecisionLogs) {
                Log.d(
                    TAG,
                    "startup timeout type=${candidate.type} " +
                        "durationMs=${(timestampNs - candidate.startTimestampNs) / 1_000_000}",
                )
            }
            startupCandidate = null
        }
    }

    private fun updateActiveCandidate(
        timestampNs: Long,
        relativePitchDeg: Float,
        relativeYawDeg: Float,
        inNeutral: Boolean,
    ) {
        val candidate = activeCandidate ?: return

        candidate.lastPitchDeg = relativePitchDeg
        candidate.lastYawDeg = relativeYawDeg
        candidate.maxPitchDeg = maxOf(candidate.maxPitchDeg, relativePitchDeg)
        candidate.minPitchDeg = minOf(candidate.minPitchDeg, relativePitchDeg)
        candidate.maxYawDeg = maxOf(candidate.maxYawDeg, relativeYawDeg)
        candidate.minYawDeg = minOf(candidate.minYawDeg, relativeYawDeg)
        candidate.maxGyroPitchRate = maxOf(candidate.maxGyroPitchRate, latestGyroValues[0])
        candidate.minGyroPitchRate = minOf(candidate.minGyroPitchRate, latestGyroValues[0])
        candidate.maxGyroYawRate = maxOf(candidate.maxGyroYawRate, latestGyroValues[2])
        candidate.minGyroYawRate = minOf(candidate.minGyroYawRate, latestGyroValues[2])

        if (timestampNs - candidate.startTimestampNs > config.maxGestureWindowNs) {
            if (config.enableDecisionLogs) {
                Log.d(TAG, "pulse timeout type=${candidate.type} durationMs=${(timestampNs - candidate.startTimestampNs) / 1_000_000}")
            }
            activeCandidate = null
            updateNeutralRefAfterPulse(timestampNs)
            return
        }

        if (!inNeutral || timestampNs - candidate.startTimestampNs < config.minGestureWindowNs) {
            return
        }

        val durationMs = (timestampNs - candidate.startTimestampNs) / 1_000_000
        if (config.enableDecisionLogs) {
            Log.d(
                TAG,
                "pulse closed type=${candidate.type} durationMs=$durationMs startPitch=${format1(candidate.startPitchDeg)} " +
                    "endPitch=${format1(candidate.lastPitchDeg)} startYaw=${format1(candidate.startYawDeg)} " +
                    "endYaw=${format1(candidate.lastYawDeg)}",
            )
        }

        when (candidate.type) {
            PulseType.NOD_DOWN -> {
                val pitchTravel = candidate.maxPitchDeg - candidate.startPitchDeg
                val totalTravel = abs(candidate.maxPitchDeg - candidate.minPitchDeg)
                val returnDelta = abs(candidate.lastPitchDeg - candidate.startPitchDeg)
                val yawTravel = maxOf(
                    abs(candidate.maxYawDeg - candidate.startYawDeg),
                    abs(candidate.minYawDeg - candidate.startYawDeg),
                )
                val valid = pitchTravel >= config.minNodTravelDeg &&
                    totalTravel >= config.nodStrongDeg &&
                    returnDelta <= config.nodReturnFromStartDeg &&
                    yawTravel <= config.nodCrossAxisMaxDeg &&
                    hasBidirectionalRate(
                        positivePeak = candidate.maxGyroPitchRate,
                        negativePeak = candidate.minGyroPitchRate,
                        minMagnitude = config.minPitchRateRad,
                    ) &&
                    hasSymmetricRate(
                        positivePeak = candidate.maxGyroPitchRate,
                        negativePeak = candidate.minGyroPitchRate,
                        maxAsymmetry = config.maxRateAsymmetry,
                    )

                if (valid) {
                    if (config.enableDecisionLogs) {
                        Log.d(
                            TAG,
                            "pulse accepted type=NOD_DOWN pitchTravel=${format1(pitchTravel)} " +
                                "totalTravel=${format1(totalTravel)} yawTravel=${format1(yawTravel)} " +
                                "returnDelta=${format1(returnDelta)} " +
                                "gyroPitch=[${format2(candidate.minGyroPitchRate)},${format2(candidate.maxGyroPitchRate)}] " +
                                "symmetry=${format2(rateSymmetry(candidate.maxGyroPitchRate, candidate.minGyroPitchRate))}",
                        )
                    }
                    recordPulse(timestampNs, PulseType.NOD_DOWN)
                } else if (config.enableDecisionLogs) {
                    Log.d(
                        TAG,
                        "pulse rejected type=NOD_DOWN durationMs=$durationMs reasons=${buildList {
                            if (pitchTravel < config.minNodTravelDeg) add("pitchTravel<${format1(config.minNodTravelDeg)}")
                            if (totalTravel < config.nodStrongDeg) add("totalTravel<${format1(config.nodStrongDeg)}")
                            if (returnDelta > config.nodReturnFromStartDeg) add("returnDelta>${format1(config.nodReturnFromStartDeg)}")
                            if (yawTravel > config.nodCrossAxisMaxDeg) add("yawTravel>${format1(config.nodCrossAxisMaxDeg)}")
                            if (!hasBidirectionalRate(
                                    positivePeak = candidate.maxGyroPitchRate,
                                    negativePeak = candidate.minGyroPitchRate,
                                    minMagnitude = config.minPitchRateRad,
                                )
                            ) add("gyroNoBidirectional")
                            if (!hasSymmetricRate(
                                    positivePeak = candidate.maxGyroPitchRate,
                                    negativePeak = candidate.minGyroPitchRate,
                                    maxAsymmetry = config.maxRateAsymmetry,
                                )
                            ) add("gyroAsymmetric")
                        }.joinToString(",")} " +
                            "pitchTravel=${format1(pitchTravel)} totalTravel=${format1(totalTravel)} " +
                            "returnDelta=${format1(returnDelta)} yawTravel=${format1(yawTravel)} " +
                            "gyroPitch=[${format2(candidate.minGyroPitchRate)},${format2(candidate.maxGyroPitchRate)}]",
                    )
                }
            }

            PulseType.SHAKE -> {
                val yawTravel = maxOf(
                    abs(candidate.maxYawDeg - candidate.startYawDeg),
                    abs(candidate.minYawDeg - candidate.startYawDeg),
                )
                val totalTravel = abs(candidate.maxYawDeg - candidate.minYawDeg)
                val returnDelta = abs(candidate.lastYawDeg - candidate.startYawDeg)
                val pitchTravel = maxOf(
                    abs(candidate.maxPitchDeg - candidate.startPitchDeg),
                    abs(candidate.minPitchDeg - candidate.startPitchDeg),
                )
                val valid = yawTravel >= config.minShakeTravelDeg &&
                    totalTravel >= config.shakeStrongDeg &&
                    returnDelta <= config.shakeReturnFromStartDeg &&
                    pitchTravel <= config.shakeCrossAxisMaxDeg &&
                    hasBidirectionalRate(
                        positivePeak = candidate.maxGyroYawRate,
                        negativePeak = candidate.minGyroYawRate,
                        minMagnitude = config.minYawRateRad,
                    ) &&
                    hasSymmetricRate(
                        positivePeak = candidate.maxGyroYawRate,
                        negativePeak = candidate.minGyroYawRate,
                        maxAsymmetry = config.maxRateAsymmetry,
                    )

                if (valid) {
                    if (config.enableDecisionLogs) {
                        Log.d(
                            TAG,
                            "pulse accepted type=SHAKE yawTravel=${format1(yawTravel)} " +
                                "totalTravel=${format1(totalTravel)} pitchTravel=${format1(pitchTravel)} " +
                                "returnDelta=${format1(returnDelta)} " +
                                "gyroYaw=[${format2(candidate.minGyroYawRate)},${format2(candidate.maxGyroYawRate)}] " +
                                "symmetry=${format2(rateSymmetry(candidate.maxGyroYawRate, candidate.minGyroYawRate))}",
                        )
                    }
                    recordPulse(timestampNs, PulseType.SHAKE)
                } else if (config.enableDecisionLogs) {
                    Log.d(
                        TAG,
                        "pulse rejected type=SHAKE durationMs=$durationMs reasons=${buildList {
                            if (yawTravel < config.minShakeTravelDeg) add("yawTravel<${format1(config.minShakeTravelDeg)}")
                            if (totalTravel < config.shakeStrongDeg) add("totalTravel<${format1(config.shakeStrongDeg)}")
                            if (returnDelta > config.shakeReturnFromStartDeg) add("returnDelta>${format1(config.shakeReturnFromStartDeg)}")
                            if (pitchTravel > config.shakeCrossAxisMaxDeg) add("pitchTravel>${format1(config.shakeCrossAxisMaxDeg)}")
                            if (!hasBidirectionalRate(
                                    positivePeak = candidate.maxGyroYawRate,
                                    negativePeak = candidate.minGyroYawRate,
                                    minMagnitude = config.minYawRateRad,
                                )
                            ) add("gyroNoBidirectional")
                            if (!hasSymmetricRate(
                                    positivePeak = candidate.maxGyroYawRate,
                                    negativePeak = candidate.minGyroYawRate,
                                    maxAsymmetry = config.maxRateAsymmetry,
                                )
                            ) add("gyroAsymmetric")
                        }.joinToString(",")} " +
                            "yawTravel=${format1(yawTravel)} totalTravel=${format1(totalTravel)} " +
                            "returnDelta=${format1(returnDelta)} pitchTravel=${format1(pitchTravel)} " +
                            "gyroYaw=[${format2(candidate.minGyroYawRate)},${format2(candidate.maxGyroYawRate)}]",
                    )
                }
            }
        }

        activeCandidate = null
        updateNeutralRefAfterPulse(timestampNs)
    }

    private fun recordPulse(timestampNs: Long, pulseType: PulseType) {
        when (pulseType) {
            PulseType.NOD_DOWN -> {
                prunePulses(nodPulseTimestamps, timestampNs)
                nodPulseTimestamps.addLast(timestampNs)
                if (config.enableDecisionLogs) {
                    Log.d(
                        TAG,
                        "aggregate updated type=NOD count=${nodPulseTimestamps.size}/2 windowMs=${config.pulseWindowNs / 1_000_000}",
                    )
                }
                if (nodPulseTimestamps.size >= 2) {
                    if (config.enableDecisionLogs) {
                        Log.d(TAG, "aggregate trigger type=NOD count=2/2")
                    }
                    nodPulseTimestamps.clear()
                    shakePulseTimestamps.clear()
                    emitAggregatedGesture(timestampNs, HeadGestureType.NOD)
                }
            }

            PulseType.SHAKE -> {
                prunePulses(shakePulseTimestamps, timestampNs)
                shakePulseTimestamps.addLast(timestampNs)
                if (config.enableDecisionLogs) {
                    Log.d(
                        TAG,
                        "aggregate updated type=SHAKE count=${shakePulseTimestamps.size}/2 windowMs=${config.pulseWindowNs / 1_000_000}",
                    )
                }
                if (shakePulseTimestamps.size >= 2) {
                    if (config.enableDecisionLogs) {
                        Log.d(TAG, "aggregate trigger type=SHAKE count=2/2")
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

    private fun shouldStartNod(relativePitchDeg: Float, relativeYawDeg: Float): Boolean {
        return abs(relativePitchDeg) >= config.nodStartPitchDeg &&
            abs(relativeYawDeg) <= config.startCrossAxisMaxDeg
    }

    private fun shouldStartShake(relativePitchDeg: Float, relativeYawDeg: Float): Boolean {
        return abs(relativeYawDeg) >= config.shakeStartYawDeg &&
            abs(relativePitchDeg) <= config.startCrossAxisMaxDeg
    }

    private fun hasBidirectionalRate(
        positivePeak: Float,
        negativePeak: Float,
        minMagnitude: Float,
    ): Boolean {
        return positivePeak >= minMagnitude && negativePeak <= -minMagnitude
    }

    /**
     * 检查角速度正负峰值的对称性。
     * 走路颠簸通常是外力导致的单向或极不对称扰动；
     * 主动点头/摇头的角速度对称性较高。
     */
    private fun hasSymmetricRate(
        positivePeak: Float,
        negativePeak: Float,
        maxAsymmetry: Float,
    ): Boolean {
        val posAbs = abs(positivePeak)
        val negAbs = abs(negativePeak)
        val maxPeak = max(posAbs, negAbs)
        if (maxPeak == 0f) return false
        return abs(posAbs - negAbs) / maxPeak <= maxAsymmetry
    }

    private fun rateSymmetry(positivePeak: Float, negativePeak: Float): Float {
        val posAbs = abs(positivePeak)
        val negAbs = abs(negativePeak)
        val maxPeak = max(posAbs, negAbs)
        if (maxPeak == 0f) return 0f
        return abs(posAbs - negAbs) / maxPeak
    }

    private fun emitGesture(type: HeadGestureType) {
        val event = HeadGestureEvent(
            type = type,
            timestampMillis = System.currentTimeMillis(),
            pitchDeg = smoothedPitchDeg,
            yawDeg = smoothedYawDeg,
            gyroPitchRate = latestGyroValues[0],
            gyroYawRate = latestGyroValues[2],
        )
        Log.i(
            TAG,
            "gesture detected type=$type relPitch=${format1(smoothedPitchDeg - neutralRefPitch)} " +
                "relYaw=${format1(smoothedYawDeg - neutralRefYaw)} absPitch=${format1(smoothedPitchDeg)} " +
                "absYaw=${format1(smoothedYawDeg)} " +
                "gyroPitch=${format3(latestGyroValues[0])} gyroYaw=${format3(latestGyroValues[2])}",
        )
        listeners.forEach { listener ->
            mainHandler.post { listener.onHeadGesture(event) }
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

    private fun maybeLogSensorSnapshot(timestampNs: Long, inNeutral: Boolean) {
        if (!config.enableSensorLogs) {
            return
        }
        if (lastSensorLogTimestampNs != 0L && timestampNs - lastSensorLogTimestampNs < SENSOR_LOG_INTERVAL_NS) {
            return
        }
        lastSensorLogTimestampNs = timestampNs
        Log.d(
            TAG,
            "sensor absPitch=${format1(smoothedPitchDeg)} absYaw=${format1(smoothedYawDeg)} " +
                "relPitch=${format1(smoothedPitchDeg - neutralRefPitch)} " +
                "relYaw=${format1(smoothedYawDeg - neutralRefYaw)} " +
                "gyroPitch=${format2(latestGyroValues[0])} gyroYaw=${format2(latestGyroValues[2])} " +
                "neutral=$inNeutral latched=$neutralLatched " +
                "startup=${startupCandidate != null} candidate=${activeCandidate != null} " +
                "nodPulses=${nodPulseTimestamps.size} shakePulses=${shakePulseTimestamps.size}",
        )
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

    private fun lowPass(previous: Float, current: Float, alpha: Float): Float {
        return previous + alpha * (current - previous)
    }

    private fun format1(value: Float): String = "%.1f".format(value)

    private fun format2(value: Float): String = "%.2f".format(value)

    private fun format3(value: Float): String = "%.3f".format(value)

    private fun resetTracking() {
        latestGyroValues = FloatArray(3)
        latestPitchDeg = 0f
        latestYawDeg = 0f
        smoothedPitchDeg = 0f
        smoothedYawDeg = 0f
        hasSmoothedPose = false
        neutralRefPitch = 0f
        neutralRefYaw = 0f
        lastYawRad = null
        unwrappedYawRad = 0f
        neutralStableSinceNs = 0L
        neutralLatched = false
        wasInNeutral = false
        activeCandidate = null
        startupCandidate = null
        lastEmitTimestampNs = 0L
        lastSensorLogTimestampNs = 0L
        nodPulseTimestamps.clear()
        shakePulseTimestamps.clear()
    }
}
