package com.rokid.glass.hiddenrisk

import com.rokid.glass.workflow.InspectionWorkflowSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class LocalTriggerDetectionServiceTest {

    @After
    fun tearDown() {
        InspectionWorkflowSession.enterpriseInfo = null
    }

    @Test
    fun detect_skipsWithoutPlaceCodeAndDoesNotRunEngine() {
        InspectionWorkflowSession.enterpriseInfo = null
        val engine = FakeNativeEngine()
        val events = mutableListOf<String>()
        val service = LocalTriggerDetectionService(
            nativeEngine = engine,
            bitmapDecoder = { error("decoder should not run") },
            worker = ImmediateExecutorService(),
            mainPoster = { it.run() },
        )

        service.detect(detectionRequest(), callback(events))

        assertEquals(listOf("success:false:"), events)
        assertFalse(engine.loadCalled)
        assertFalse(engine.detectCalled)
    }

    @Test
    fun detect_returnsLabelsWhenNativeStatsContainDetections() {
        setPlaceCode()
        val engine = FakeNativeEngine(
            stats = NativeInferenceStats(
                1,
                "System Vulkan",
                1,
                "Balanced FP16",
                640,
                "GPU",
                640,
                640,
                18L,
                "",
                0,
                "",
                1,
                1,
                arrayOf(DetectionResult("煤炉", 0f, 0f, 10f, 10f, 0.88f, 1)),
            ),
        )
        val events = mutableListOf<String>()
        val service = LocalTriggerDetectionService(
            nativeEngine = engine,
            bitmapDecoder = { FakeBitmapToken },
            worker = ImmediateExecutorService(),
            mainPoster = { it.run() },
        )

        service.detect(detectionRequest(), callback(events))

        assertTrue(engine.loadCalled)
        assertTrue(engine.detectCalled)
        assertEquals(listOf("success:true:煤炉"), events)
    }

    @Test
    fun detect_failureWhenBitmapDecodeFails() {
        setPlaceCode()
        val events = mutableListOf<String>()
        val service = LocalTriggerDetectionService(
            nativeEngine = FakeNativeEngine(),
            bitmapDecoder = { null },
            worker = ImmediateExecutorService(),
            mainPoster = { it.run() },
        )

        service.detect(detectionRequest(), callback(events))

        assertEquals(listOf("failure:本地触发图片解码失败"), events)
    }

    @Test
    fun detect_cancelSuppressesCallback() {
        setPlaceCode()
        val events = mutableListOf<String>()
        lateinit var deferred: Runnable
        val service = LocalTriggerDetectionService(
            nativeEngine = FakeNativeEngine(),
            bitmapDecoder = { FakeBitmapToken },
            worker = ImmediateExecutorService(),
            mainPoster = { deferred = it },
        )

        val handle = service.detect(detectionRequest(), callback(events))
        handle.cancel()
        deferred.run()

        assertTrue(events.isEmpty())
    }

    private fun detectionRequest(): OnlineHazardDetectionService.DetectionRequest {
        return OnlineHazardDetectionService.DetectionRequest(
            epoch = 1L,
            requestId = 9L,
            jpegBytes = byteArrayOf(1, 2, 3),
        )
    }

    private fun setPlaceCode() {
        InspectionWorkflowSession.enterpriseInfo =
            InspectionWorkflowSession.EnterpriseInfo(
                companyName = "test",
                siteName = "test",
                inspectorName = "test",
                qrContent = "test",
                placeCode = "PLACE-001",
            )
    }

    private fun callback(events: MutableList<String>): AiArSseService.DetectCallback {
        return object : AiArSseService.DetectCallback {
            override fun onOpened(handle: AiArSseService.RequestHandle) = Unit

            override fun onSuccess(
                handle: AiArSseService.RequestHandle,
                hasHazard: Boolean,
                fullText: String,
                labels: List<String>,
            ) {
                events += "success:$hasHazard:${labels.joinToString()}"
            }

            override fun onFailure(handle: AiArSseService.RequestHandle, message: String) {
                events += "failure:$message"
            }
        }
    }

    private object FakeBitmapToken

    private class FakeNativeEngine(
        private val stats: NativeInferenceStats = NativeInferenceStats(
            1,
            "System Vulkan",
            1,
            "Balanced FP16",
            640,
            "GPU",
            640,
            640,
            10L,
            "",
            0,
            "",
            0,
            0,
            emptyArray(),
        ),
    ) : LocalTriggerDetectionService.NativeEngine {
        var loadCalled = false
        var detectCalled = false

        override fun ensureLoaded(): Boolean {
            loadCalled = true
            return true
        }

        override fun submitBitmap(bitmap: Any): Boolean {
            detectCalled = true
            return true
        }

        override fun latestStats(): NativeInferenceStats = stats
    }

    private class ImmediateExecutorService : AbstractExecutorService() {
        private var shutdown = false

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true

        override fun execute(command: Runnable) {
            command.run()
        }
    }
}
