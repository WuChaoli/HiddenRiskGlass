package com.rokid.glass.hiddenrisk

import com.rokid.glass.workflow.InspectionWorkflowSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTriggerDetectionServiceTest {

    @After
    fun tearDown() {
        InspectionWorkflowSession.enterpriseInfo = null
    }

    @Test
    fun detectRunsCoordinatorWithoutPlaceCode() {
        val coordinator = FakeCoordinator(successOutcome("燃气灶"))
        val events = mutableListOf<String>()

        createService(coordinator).detect(detectionRequest(), callback(events))

        assertEquals(listOf(FakeBitmapToken), coordinator.bitmaps)
        assertEquals(listOf("success:true:燃气灶"), events)
    }

    @Test
    fun nonRuleLabelDoesNotTriggerHazard() {
        setPlaceCode()
        val coordinator = FakeCoordinator(
            outcome = LocalInferenceCoordinator.DetectionOutcome(
                success = true,
                stats = statsWithLabel("煤炉"),
                errorMessage = "",
            ),
        )
        val events = mutableListOf<String>()

        createService(coordinator).detect(detectionRequest(), callback(events))

        assertEquals(listOf(FakeBitmapToken), coordinator.bitmaps)
        assertEquals(listOf("success:false:煤炉"), events)
    }

    @Test
    fun missingProtectionTriggersHazard() {
        setPlaceCode()
        val events = mutableListOf<String>()
        createService(FakeCoordinator(successOutcome("燃气灶"))).detect(detectionRequest(), callback(events))
        assertEquals(listOf("success:true:燃气灶"), events)
    }

    @Test
    fun presentProtectionSuppressesHazard() {
        setPlaceCode()
        val events = mutableListOf<String>()
        createService(FakeCoordinator(successOutcome("燃气灶", "熄火保护装置")))
            .detect(detectionRequest(), callback(events))
        assertEquals(listOf("success:false:燃气灶, 熄火保护装置"), events)
    }

    @Test
    fun detectFailsWhenBitmapDecodeFails() {
        setPlaceCode()
        val events = mutableListOf<String>()
        val service = createService(FakeCoordinator(), decoder = { null })

        service.detect(detectionRequest(), callback(events))

        assertEquals(listOf("failure:本地触发图片解码失败"), events)
    }

    @Test
    fun canceledHandleSuppressesPostedCallback() {
        setPlaceCode()
        val events = mutableListOf<String>()
        lateinit var posted: Runnable
        val service = createService(
            coordinator = FakeCoordinator(),
            mainPoster = { posted = it },
        )

        val handle = service.detect(detectionRequest(), callback(events))
        handle.cancel()
        posted.run()

        assertTrue(events.isEmpty())
    }

    @Test
    fun shutdownSuppressesDeferredResultWithoutCancelingCoordinatorTask() {
        setPlaceCode()
        val coordinator = DeferredCoordinator()
        val events = mutableListOf<String>()
        var recycleCount = 0
        val service = createService(
            coordinator = coordinator,
            recycler = { recycleCount += 1 },
        )

        service.detect(detectionRequest(), callback(events))
        service.shutdown()
        coordinator.complete(successOutcome())

        assertTrue(events.isEmpty())
        assertFalse(coordinator.taskCanceled)
        assertEquals(1, recycleCount)
    }

    private fun createService(
        coordinator: LocalTriggerDetectionService.CoordinatorGateway,
        decoder: (ByteArray) -> Any? = { FakeBitmapToken },
        recycler: (Any) -> Unit = {},
        mainPoster: (Runnable) -> Unit = { it.run() },
    ): LocalTriggerDetectionService {
        return LocalTriggerDetectionService(
            assetManager = FakeAssets,
            coordinator = coordinator,
            bitmapDecoder = decoder,
            bitmapRecycler = recycler,
            mainPoster = mainPoster,
            elapsedRealtimeProvider = { 100L },
            infoLogger = {},
            warningLogger = {},
        )
    }

    private fun detectionRequest(requestId: Long = 9L): OnlineHazardDetectionService.DetectionRequest {
        return OnlineHazardDetectionService.DetectionRequest(
            epoch = 1L,
            requestId = requestId,
            jpegBytes = byteArrayOf(1, 2, 3),
        )
    }

    private fun setPlaceCode() {
        InspectionWorkflowSession.enterpriseInfo = InspectionWorkflowSession.EnterpriseInfo(
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

    private class FakeCoordinator(
        private val outcome: LocalInferenceCoordinator.DetectionOutcome = successOutcome(),
    ) : LocalTriggerDetectionService.CoordinatorGateway {
        val bitmaps = mutableListOf<Any>()

        override fun detect(
            assets: Any,
            bitmap: Any,
            traceLabel: String,
            callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
        ) {
            bitmaps += bitmap
            callback(outcome)
        }
    }

    private class DeferredCoordinator : LocalTriggerDetectionService.CoordinatorGateway {
        private lateinit var callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit
        var taskCanceled = false

        override fun detect(
            assets: Any,
            bitmap: Any,
            traceLabel: String,
            callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
        ) {
            this.callback = callback
        }

        fun complete(outcome: LocalInferenceCoordinator.DetectionOutcome) {
            callback(outcome)
        }
    }

    private object FakeAssets
    private object FakeBitmapToken

    companion object {
        private fun successOutcome(): LocalInferenceCoordinator.DetectionOutcome {
            return LocalInferenceCoordinator.DetectionOutcome(true, emptyStats(), "")
        }

        private fun successOutcome(vararg labels: String): LocalInferenceCoordinator.DetectionOutcome {
            return LocalInferenceCoordinator.DetectionOutcome(true, statsWithLabels(*labels), "")
        }

        private fun statsWithLabel(label: String): NativeInferenceStats {
            return statsWithLabels(label)
        }

        private fun statsWithLabels(vararg labels: String): NativeInferenceStats {
            return NativeInferenceStats(
                1, "System Vulkan", 1, "Balanced FP16", 640, "GPU", 640, 640, 18L,
                "", 0, "", labels.size, labels.size,
                labels.map { label ->
                    DetectionResult(label, 0f, 0f, 10f, 10f, 0.88f, labelId(label))
                }.toTypedArray(),
            )
        }

        private fun labelId(label: String): Int = when (label) {
            "煤炉" -> 22
            "熄火保护装置" -> 24
            "燃气灶" -> 25
            else -> error("unsupported test label=$label")
        }

        private fun emptyStats(): NativeInferenceStats {
            return NativeInferenceStats(
                1, "System Vulkan", 1, "Balanced FP16", 640, "GPU", 640, 640, 10L,
                "", 0, "", 0, 0, emptyArray(),
            )
        }
    }
}
