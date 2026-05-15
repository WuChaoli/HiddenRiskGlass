package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferencePressureMonitorTest {

    @Test
    fun thermalLevel_mapsOfficialStatusesToThreeLevels() {
        assertEquals(PressureLevel.LOW, PressureClassifiers.thermalLevel(0))
        assertEquals(PressureLevel.LOW, PressureClassifiers.thermalLevel(1))
        assertEquals(PressureLevel.MEDIUM, PressureClassifiers.thermalLevel(2))
        assertEquals(PressureLevel.HIGH, PressureClassifiers.thermalLevel(3))
        assertEquals(PressureLevel.HIGH, PressureClassifiers.thermalLevel(6))
    }

    @Test
    fun memoryLevel_promotesLowMemoryAndThresholdBuckets() {
        assertEquals(
            PressureLevel.HIGH,
            PressureClassifiers.memoryLevel(
                lowMemory = true,
                availMemPercent = 40,
            ),
        )
        assertEquals(
            PressureLevel.HIGH,
            PressureClassifiers.memoryLevel(
                lowMemory = false,
                availMemPercent = 14,
            ),
        )
        assertEquals(
            PressureLevel.MEDIUM,
            PressureClassifiers.memoryLevel(
                lowMemory = false,
                availMemPercent = 15,
            ),
        )
        assertEquals(
            PressureLevel.LOW,
            PressureClassifiers.memoryLevel(
                lowMemory = false,
                availMemPercent = 25,
            ),
        )
    }

    @Test
    fun inferenceLevel_usesLatencyThresholdsAndFailureEscalation() {
        assertEquals(
            PressureLevel.LOW,
            PressureClassifiers.inferenceLevel(
                inferenceTimeMs = 349L,
                success = true,
            ),
        )
        assertEquals(
            PressureLevel.MEDIUM,
            PressureClassifiers.inferenceLevel(
                inferenceTimeMs = 350L,
                success = true,
            ),
        )
        assertEquals(
            PressureLevel.HIGH,
            PressureClassifiers.inferenceLevel(
                inferenceTimeMs = 700L,
                success = true,
            ),
        )
        assertEquals(
            PressureLevel.HIGH,
            PressureClassifiers.inferenceLevel(
                inferenceTimeMs = 100L,
                success = false,
            ),
        )
    }

    @Test
    fun cpuAndGpuLevel_remainStableUnderCombinedSignals() {
        assertEquals(
            PressureLevel.HIGH,
            PressureClassifiers.cpuLevel(
                processCpuLoadApproxPercent = 10,
                thermalLevel = PressureLevel.HIGH,
                inferenceLevel = PressureLevel.LOW,
            ),
        )
        assertEquals(
            PressureLevel.MEDIUM,
            PressureClassifiers.cpuLevel(
                processCpuLoadApproxPercent = 45,
                thermalLevel = PressureLevel.LOW,
                inferenceLevel = PressureLevel.LOW,
            ),
        )
        assertEquals(
            PressureLevel.HIGH,
            PressureClassifiers.gpuLevel(
                backendName = "System Vulkan",
                thermalLevel = PressureLevel.MEDIUM,
                inferenceLevel = PressureLevel.HIGH,
            ),
        )
        assertEquals(
            PressureLevel.LOW,
            PressureClassifiers.gpuLevel(
                backendName = "CPU",
                thermalLevel = PressureLevel.HIGH,
                inferenceLevel = PressureLevel.HIGH,
            ),
        )
    }

    @Test
    fun pressureSnapshot_formatsSingleLineStructuredPayload() {
        val snapshot = PressureSnapshot(
            seq = 7L,
            timestampMs = 123456789L,
            workflowState = "INFERRING",
            cpu = CpuPressure(
                cpuCoreCount = 8,
                processCpuTimeDeltaMs = 30L,
                wallTimeDeltaMs = 100L,
                processCpuLoadApproxPercent = 38,
                cpuLevel = PressureLevel.MEDIUM,
            ),
            gpu = GpuPressure(
                backendName = "System Vulkan",
                gpuProfileName = "Balanced FP16",
                deviceName = "GPU-X",
                gpuLevel = PressureLevel.MEDIUM,
            ),
            memory = MemoryPressure(
                availMemBytes = 100L,
                totalMemBytes = 400L,
                thresholdBytes = 50L,
                lowMemory = false,
                availMemPercent = 25,
                javaUsedBytes = 200L,
                javaMaxBytes = 500L,
                nativeHeapAllocatedBytes = 300L,
                memoryLevel = PressureLevel.LOW,
            ),
            thermal = ThermalPressure(
                thermalStatus = 2,
                thermalStatusName = "MODERATE",
                thermalLevel = PressureLevel.MEDIUM,
            ),
            inference = InferencePressure(
                backendName = "System Vulkan",
                gpuProfileName = "Balanced FP16",
                targetSize = 640,
                inferenceTimeMs = 420L,
                detectionCount = 3,
                preLimitDetectionCount = 5,
                errorStage = "N/A",
                errorCode = 0,
                success = true,
                inferenceLevel = PressureLevel.MEDIUM,
            ),
        )

        val logLine = snapshot.toLogLine()

        assertTrue(logLine.startsWith("pressure snapshot seq=7"))
        assertTrue(logLine.contains("workflowState=INFERRING"))
        assertTrue(logLine.contains("cpu={level=MEDIUM"))
        assertTrue(logLine.contains("gpu={level=MEDIUM"))
        assertTrue(logLine.contains("memory={level=LOW"))
        assertTrue(logLine.contains("thermal={level=MEDIUM"))
        assertTrue(logLine.contains("inference={level=MEDIUM"))
    }
}
