package com.rokid.glass.hiddenrisk

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.math.roundToInt

internal enum class PressureLevel {
    LOW,
    MEDIUM,
    HIGH,
}

internal data class ThermalPressure(
    val thermalStatus: Int,
    val thermalStatusName: String,
    val thermalLevel: PressureLevel,
)

internal data class MemoryPressure(
    val availMemBytes: Long,
    val totalMemBytes: Long,
    val thresholdBytes: Long,
    val lowMemory: Boolean,
    val availMemPercent: Int,
    val javaUsedBytes: Long,
    val javaMaxBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    val memoryLevel: PressureLevel,
)

internal data class CpuPressure(
    val cpuCoreCount: Int,
    val processCpuTimeDeltaMs: Long,
    val wallTimeDeltaMs: Long,
    val processCpuLoadApproxPercent: Int,
    val cpuLevel: PressureLevel,
)

internal data class GpuPressure(
    val backendName: String,
    val gpuProfileName: String,
    val deviceName: String,
    val gpuLevel: PressureLevel,
)

internal data class InferencePressure(
    val backendName: String,
    val gpuProfileName: String,
    val targetSize: Int,
    val inferenceTimeMs: Long,
    val detectionCount: Int,
    val preLimitDetectionCount: Int,
    val errorStage: String,
    val errorCode: Int,
    val success: Boolean,
    val inferenceLevel: PressureLevel,
)

internal data class PressureSnapshot(
    val seq: Long,
    val timestampMs: Long,
    val workflowState: String,
    val cpu: CpuPressure,
    val gpu: GpuPressure,
    val memory: MemoryPressure,
    val thermal: ThermalPressure,
    val inference: InferencePressure,
) {
    fun toLogLine(): String {
        return buildString {
            append("pressure snapshot")
            append(" seq=").append(seq)
            append(" timestamp=").append(timestampMs)
            append(" workflowState=").append(workflowState)
            append(" cpu={")
            append("level=").append(cpu.cpuLevel)
            append(",coreCount=").append(cpu.cpuCoreCount)
            append(",processCpuTimeDeltaMs=").append(cpu.processCpuTimeDeltaMs)
            append(",wallTimeDeltaMs=").append(cpu.wallTimeDeltaMs)
            append(",processCpuLoadApproxPercent=").append(cpu.processCpuLoadApproxPercent)
            append("}")
            append(" gpu={")
            append("level=").append(gpu.gpuLevel)
            append(",backendName=").append(gpu.backendName)
            append(",gpuProfileName=").append(gpu.gpuProfileName)
            append(",deviceName=").append(gpu.deviceName)
            append("}")
            append(" memory={")
            append("level=").append(memory.memoryLevel)
            append(",availMemBytes=").append(memory.availMemBytes)
            append(",totalMemBytes=").append(memory.totalMemBytes)
            append(",thresholdBytes=").append(memory.thresholdBytes)
            append(",lowMemory=").append(memory.lowMemory)
            append(",availMemPercent=").append(memory.availMemPercent)
            append(",javaUsedBytes=").append(memory.javaUsedBytes)
            append(",javaMaxBytes=").append(memory.javaMaxBytes)
            append(",nativeHeapAllocatedBytes=").append(memory.nativeHeapAllocatedBytes)
            append("}")
            append(" thermal={")
            append("level=").append(thermal.thermalLevel)
            append(",thermalStatus=").append(thermal.thermalStatus)
            append(",thermalStatusName=").append(thermal.thermalStatusName)
            append("}")
            append(" inference={")
            append("level=").append(inference.inferenceLevel)
            append(",success=").append(inference.success)
            append(",backendName=").append(inference.backendName)
            append(",gpuProfileName=").append(inference.gpuProfileName)
            append(",targetSize=").append(inference.targetSize)
            append(",inferenceTimeMs=").append(inference.inferenceTimeMs)
            append(",detectionCount=").append(inference.detectionCount)
            append(",preLimitDetectionCount=").append(inference.preLimitDetectionCount)
            append(",errorStage=").append(inference.errorStage)
            append(",errorCode=").append(inference.errorCode)
            append("}")
        }
    }
}

internal object PressureClassifiers {

    fun thermalLevel(thermalStatus: Int): PressureLevel {
        return when (thermalStatus) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> PressureLevel.LOW
            PowerManager.THERMAL_STATUS_MODERATE -> PressureLevel.MEDIUM
            else -> PressureLevel.HIGH
        }
    }

    fun thermalStatusName(thermalStatus: Int): String {
        return when (thermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }
    }

    fun memoryLevel(
        lowMemory: Boolean,
        availMemPercent: Int,
    ): PressureLevel {
        return when {
            lowMemory || availMemPercent in 0..14 -> PressureLevel.HIGH
            availMemPercent in 15..24 -> PressureLevel.MEDIUM
            else -> PressureLevel.LOW
        }
    }

    fun inferenceLevel(
        inferenceTimeMs: Long,
        success: Boolean,
    ): PressureLevel {
        return when {
            !success || inferenceTimeMs >= 700L || inferenceTimeMs < 0L -> PressureLevel.HIGH
            inferenceTimeMs >= 350L -> PressureLevel.MEDIUM
            else -> PressureLevel.LOW
        }
    }

    fun cpuLevel(
        processCpuLoadApproxPercent: Int,
        thermalLevel: PressureLevel,
        inferenceLevel: PressureLevel,
    ): PressureLevel {
        return when {
            processCpuLoadApproxPercent >= 80 ||
                thermalLevel == PressureLevel.HIGH ||
                inferenceLevel == PressureLevel.HIGH -> PressureLevel.HIGH
            processCpuLoadApproxPercent >= 40 ||
                thermalLevel == PressureLevel.MEDIUM ||
                inferenceLevel == PressureLevel.MEDIUM -> PressureLevel.MEDIUM
            else -> PressureLevel.LOW
        }
    }

    fun gpuLevel(
        backendName: String,
        thermalLevel: PressureLevel,
        inferenceLevel: PressureLevel,
    ): PressureLevel {
        val isGpuBackend = backendName == "System Vulkan" || backendName == "Turnip"
        if (!isGpuBackend) {
            return PressureLevel.LOW
        }
        return when {
            thermalLevel == PressureLevel.HIGH || inferenceLevel == PressureLevel.HIGH -> PressureLevel.HIGH
            thermalLevel == PressureLevel.MEDIUM || inferenceLevel == PressureLevel.MEDIUM -> PressureLevel.MEDIUM
            else -> PressureLevel.LOW
        }
    }
}

internal class InferencePressureMonitor(
    context: Context,
    private val tag: String,
    private val nowElapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val nowWallClockMs: () -> Long = { System.currentTimeMillis() },
    private val processCpuTimeMs: () -> Long = { Process.getElapsedCpuTime() },
) {

    private val appContext = context.applicationContext
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var seq = 0L
    private var lastCpuSampleElapsedMs: Long? = null
    private var lastProcessCpuTimeMs: Long? = null

    fun startSession() {
        clearSession()
        val nowElapsedMs = nowElapsedRealtimeMs()
        lastCpuSampleElapsedMs = nowElapsedMs
        lastProcessCpuTimeMs = processCpuTimeMs()
    }

    fun clearSession() {
        seq = 0L
        lastCpuSampleElapsedMs = null
        lastProcessCpuTimeMs = null
    }

    fun logSnapshot(
        workflowState: String,
        stats: NativeInferenceStats?,
        success: Boolean,
    ): PressureSnapshot {
        val snapshot = buildSnapshot(
            workflowState = workflowState,
            stats = stats,
            success = success,
        )
        Log.i(tag, snapshot.toLogLine())
        return snapshot
    }

    private fun buildSnapshot(
        workflowState: String,
        stats: NativeInferenceStats?,
        success: Boolean,
    ): PressureSnapshot {
        val inference = buildInferencePressure(stats, success)
        val thermal = buildThermalPressure()
        val memory = buildMemoryPressure()
        val cpu = buildCpuPressure(
            thermalLevel = thermal.thermalLevel,
            inferenceLevel = inference.inferenceLevel,
        )
        val gpu = buildGpuPressure(
            stats = stats,
            thermalLevel = thermal.thermalLevel,
            inferenceLevel = inference.inferenceLevel,
        )
        seq += 1L
        return PressureSnapshot(
            seq = seq,
            timestampMs = nowWallClockMs(),
            workflowState = workflowState,
            cpu = cpu,
            gpu = gpu,
            memory = memory,
            thermal = thermal,
            inference = inference,
        )
    }

    private fun buildInferencePressure(
        stats: NativeInferenceStats?,
        success: Boolean,
    ): InferencePressure {
        val backendName = stats?.backendName?.takeIf { it.isNotBlank() }
            ?: HiddenRiskNcnn.backendLabel(stats?.backendId ?: HiddenRiskNcnn.BACKEND_UNKNOWN)
        val gpuProfileName = stats?.gpuProfileName?.takeIf { it.isNotBlank() }
            ?: HiddenRiskNcnn.gpuProfileLabel(stats?.gpuProfileId ?: -1)
        val targetSize = stats?.targetSize ?: -1
        val inferenceTimeMs = stats?.inferenceTimeMs ?: -1L
        val detectionCount = stats?.detectionCount ?: -1
        val preLimitDetectionCount = stats?.preLimitDetectionCount ?: -1
        val errorStage = stats?.errorStage?.takeIf { it.isNotBlank() } ?: "N/A"
        val errorCode = stats?.errorCode ?: -1
        return InferencePressure(
            backendName = backendName,
            gpuProfileName = gpuProfileName,
            targetSize = targetSize,
            inferenceTimeMs = inferenceTimeMs,
            detectionCount = detectionCount,
            preLimitDetectionCount = preLimitDetectionCount,
            errorStage = errorStage,
            errorCode = errorCode,
            success = success,
            inferenceLevel = PressureClassifiers.inferenceLevel(
                inferenceTimeMs = inferenceTimeMs,
                success = success,
            ),
        )
    }

    private fun buildThermalPressure(): ThermalPressure {
        val thermalStatus = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        return ThermalPressure(
            thermalStatus = thermalStatus,
            thermalStatusName = PressureClassifiers.thermalStatusName(thermalStatus),
            thermalLevel = PressureClassifiers.thermalLevel(thermalStatus),
        )
    }

    private fun buildMemoryPressure(): MemoryPressure {
        val runtime = Runtime.getRuntime()
        val javaUsedBytes = runtime.totalMemory() - runtime.freeMemory()
        val javaMaxBytes = runtime.maxMemory()
        val nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize()
        val memoryInfo = ActivityManager.MemoryInfo().also { info ->
            activityManager?.getMemoryInfo(info)
        }
        val availMemPercent = if (memoryInfo.totalMem > 0L) {
            ((memoryInfo.availMem.toDouble() / memoryInfo.totalMem.toDouble()) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        } else {
            0
        }
        return MemoryPressure(
            availMemBytes = memoryInfo.availMem,
            totalMemBytes = memoryInfo.totalMem,
            thresholdBytes = memoryInfo.threshold,
            lowMemory = memoryInfo.lowMemory,
            availMemPercent = availMemPercent,
            javaUsedBytes = javaUsedBytes,
            javaMaxBytes = javaMaxBytes,
            nativeHeapAllocatedBytes = nativeHeapAllocatedBytes,
            memoryLevel = PressureClassifiers.memoryLevel(
                lowMemory = memoryInfo.lowMemory,
                availMemPercent = availMemPercent,
            ),
        )
    }

    private fun buildCpuPressure(
        thermalLevel: PressureLevel,
        inferenceLevel: PressureLevel,
    ): CpuPressure {
        val nowElapsedMs = nowElapsedRealtimeMs()
        val nowProcessCpuMs = processCpuTimeMs()
        val previousElapsedMs = lastCpuSampleElapsedMs
        val previousProcessCpuMs = lastProcessCpuTimeMs
        val wallTimeDeltaMs = if (previousElapsedMs == null) 0L else (nowElapsedMs - previousElapsedMs).coerceAtLeast(0L)
        val processCpuTimeDeltaMs = if (previousProcessCpuMs == null) 0L else (nowProcessCpuMs - previousProcessCpuMs).coerceAtLeast(0L)
        lastCpuSampleElapsedMs = nowElapsedMs
        lastProcessCpuTimeMs = nowProcessCpuMs

        val cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val processCpuLoadApproxPercent = if (wallTimeDeltaMs <= 0L) {
            0
        } else {
            ((processCpuTimeDeltaMs.toDouble() / (wallTimeDeltaMs.toDouble() * cpuCoreCount.toDouble())) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        }
        return CpuPressure(
            cpuCoreCount = cpuCoreCount,
            processCpuTimeDeltaMs = processCpuTimeDeltaMs,
            wallTimeDeltaMs = wallTimeDeltaMs,
            processCpuLoadApproxPercent = processCpuLoadApproxPercent,
            cpuLevel = PressureClassifiers.cpuLevel(
                processCpuLoadApproxPercent = processCpuLoadApproxPercent,
                thermalLevel = thermalLevel,
                inferenceLevel = inferenceLevel,
            ),
        )
    }

    private fun buildGpuPressure(
        stats: NativeInferenceStats?,
        thermalLevel: PressureLevel,
        inferenceLevel: PressureLevel,
    ): GpuPressure {
        val backendName = stats?.backendName?.takeIf { it.isNotBlank() }
            ?: HiddenRiskNcnn.backendLabel(stats?.backendId ?: HiddenRiskNcnn.BACKEND_UNKNOWN)
        val gpuProfileName = stats?.gpuProfileName?.takeIf { it.isNotBlank() }
            ?: HiddenRiskNcnn.gpuProfileLabel(stats?.gpuProfileId ?: -1)
        val deviceName = stats?.deviceName?.takeIf { it.isNotBlank() } ?: "N/A"
        return GpuPressure(
            backendName = backendName,
            gpuProfileName = gpuProfileName,
            deviceName = deviceName,
            gpuLevel = PressureClassifiers.gpuLevel(
                backendName = backendName,
                thermalLevel = thermalLevel,
                inferenceLevel = inferenceLevel,
            ),
        )
    }
}
