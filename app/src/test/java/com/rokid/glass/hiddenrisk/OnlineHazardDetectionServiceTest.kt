package com.rokid.glass.hiddenrisk

import com.rokid.glass.config.AutoDetectProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineHazardDetectionServiceTest {

    @Test
    fun submitDetection_acceptsFiveConcurrentRequestsAndDropsSixth() {
        val env = TestEnv()
        val service = env.createService()

        (1L..6L).forEach { requestId ->
            service.submitDetection(detectionRequest(requestId = requestId))
        }

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), env.gateway.startedDetectionRequestIds)
        assertEquals(listOf("drop:6:busy"), env.callbackEvents)
    }

    @Test
    fun submitDetection_timesOutAfterOneAndHalfSecondsAndCancelsHandle() {
        val env = TestEnv()
        val service = env.createService()

        val request = detectionRequest(requestId = 7L)
        service.submitDetection(request)

        env.advanceTimeBy(1499L)
        assertTrue(env.callbackEvents.isEmpty())
        assertFalse(env.gateway.detectionHandles[7L]?.isCanceled() ?: true)

        env.advanceTimeBy(1L)

        assertEquals(listOf("drop:7:timeout"), env.callbackEvents)
        assertTrue(env.gateway.detectionHandles[7L]?.isCanceled() ?: false)
    }

    @Test
    fun submitDetection_releasesOnlyCompletedRequestSlotAfterSuccess() {
        val env = TestEnv()
        val service = env.createService()

        (11L..15L).forEach { requestId ->
            service.submitDetection(detectionRequest(requestId = requestId))
        }
        env.gateway.completeDetectionSuccess(requestId = 11L, hasHazard = false, fullText = "否")
        service.submitDetection(detectionRequest(requestId = 16L))

        assertEquals(listOf(11L, 12L, 13L, 14L, 15L, 16L), env.gateway.startedDetectionRequestIds)
        assertEquals(listOf("result:11:false"), env.callbackEvents)
    }

    @Test
    fun submitDetection_sceneLaneWorks() {
        val env = TestEnv()
        val service = env.createService()

        val request = detectionRequest(
            requestId = 23L,
            lane = OnlineHazardDetectionService.DetectionLane.SCENE,
        )
        service.submitDetection(request)

        assertEquals(listOf(23L), env.gateway.startedDetectionRequestIds)
        assertEquals(
            listOf(OnlineHazardDetectionService.DetectionLane.SCENE),
            env.gateway.startedDetectionLanes,
        )
    }

    @Test
    fun submitDetection_passesRawJpegBytesToGateway() {
        val env = TestEnv()
        val service = env.createService()
        val request = detectionRequest(requestId = 41L)

        service.submitDetection(request)

        assertEquals(listOf(41L), env.gateway.startedDetectionRequestIds)
        assertTrue(env.gateway.startedDetectionJpegBytes.single().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun cancelActiveDetection_cancelsAllActiveHandlesAndIgnoresOldCallbacks() {
        val env = TestEnv()
        val service = env.createService()

        service.submitDetection(detectionRequest(requestId = 19L))
        service.submitDetection(detectionRequest(requestId = 20L))

        service.cancelActiveDetection()
        env.gateway.completeDetectionSuccess(
            requestId = 19L,
            hasHazard = true,
            fullText = "是",
        )
        env.gateway.completeDetectionSuccess(
            requestId = 20L,
            hasHazard = false,
            fullText = "否",
        )

        assertTrue(env.callbackEvents.isEmpty())
        assertTrue(env.gateway.detectionHandles[19L]?.isCanceled() ?: false)
        assertTrue(env.gateway.detectionHandles[20L]?.isCanceled() ?: false)
    }

    @Test
    fun requestDeepAnalysis_keepsItemLaneForDefaultDetail() {
        val env = TestEnv()
        val service = env.createService()

        service.requestDeepAnalysis(
            OnlineHazardDetectionService.DetailRequest(
                epoch = 1L,
                requestId = 31L,
                jpegBytes = byteArrayOf(1),
            ),
        )

        assertEquals(
            listOf(OnlineHazardDetectionService.DetectionLane.ITEM),
            env.gateway.startedDetailLanes,
        )
    }

    @Test
    fun requestDeepAnalysis_preservesSceneLaneForGeneralDeepDetail() {
        val env = TestEnv()
        val service = env.createService()

        service.requestDeepAnalysis(
            OnlineHazardDetectionService.DetailRequest(
                epoch = 1L,
                requestId = 32L,
                jpegBytes = byteArrayOf(1),
                lane = OnlineHazardDetectionService.DetectionLane.SCENE,
            ),
        )

        assertEquals(
            listOf(OnlineHazardDetectionService.DetectionLane.SCENE),
            env.gateway.startedDetailLanes,
        )
    }

    @Test
    fun defaultGatewayFactory_usesHttpGatewayForHttpProvider() {
        val gateway = OnlineHazardDetectionService.createDefaultRequestGateway(
            provider = AutoDetectProvider.HTTP,
            localTriggerDetectionService = null,
            base64Encoder = { "encoded" },
        )

        assertEquals("SseRequestGateway", gateway.javaClass.simpleName)
    }

    @Test
    fun defaultGatewayFactory_usesLocalGatewayForLocalProvider() {
        val gateway = OnlineHazardDetectionService.createDefaultRequestGateway(
            provider = AutoDetectProvider.LOCAL_TRIGGER,
            localTriggerDetectionService = LocalTriggerDetectionService(
                assetManager = Any(),
                coordinator = FakeLocalCoordinator(),
                bitmapDecoder = { null },
                mainPoster = { it.run() },
            ),
            base64Encoder = { "encoded" },
        )

        assertEquals("LocalTriggerRequestGateway", gateway.javaClass.simpleName)
    }

    @Test
    fun cancelActiveDetection_doesNotCancelActiveDeepAnalysis() {
        val env = TestEnv()
        val service = env.createService()

        service.requestDeepAnalysis(
            OnlineHazardDetectionService.DetailRequest(
                epoch = 1L,
                requestId = 33L,
                jpegBytes = byteArrayOf(1),
            ),
        )
        service.submitDetection(detectionRequest(requestId = 34L))

        service.cancelActiveDetection()

        assertFalse(env.gateway.detailHandle?.isCanceled() ?: true)
        assertTrue(env.gateway.detectionHandles[34L]?.isCanceled() ?: false)
    }

    private fun detectionRequest(
        requestId: Long,
        lane: OnlineHazardDetectionService.DetectionLane = OnlineHazardDetectionService.DetectionLane.ITEM,
    ): OnlineHazardDetectionService.DetectionRequest {
        return OnlineHazardDetectionService.DetectionRequest(
            epoch = 1L,
            requestId = requestId,
            jpegBytes = byteArrayOf(1, 2, 3),
            lane = lane,
        )
    }

    private class TestEnv {
        var nowElapsedMs = 0L
        val scheduler = FakeScheduler { nowElapsedMs }
        val gateway = FakeRequestGateway()
        val callbackEvents = mutableListOf<String>()

        fun createService(): OnlineHazardDetectionService {
            return OnlineHazardDetectionService(
                callback = object : OnlineHazardDetectionService.Callback {
                    override fun onDetectionResult(
                        request: OnlineHazardDetectionService.DetectionRequest,
                        hasHazard: Boolean,
                        rawText: String,
                        labels: List<String>,
                    ) {
                        callbackEvents += "result:${request.requestId}:$hasHazard"
                    }

                    override fun onDetectionFailure(
                        request: OnlineHazardDetectionService.DetectionRequest,
                        message: String,
                    ) {
                        callbackEvents += "failure:${request.requestId}:$message"
                    }

                    override fun onDetectionDropped(
                        request: OnlineHazardDetectionService.DetectionRequest,
                        reason: String,
                    ) {
                        callbackEvents += "drop:${request.requestId}:$reason"
                    }

                    override fun onDeepAnalysisChunk(
                        request: OnlineHazardDetectionService.DetailRequest,
                        accumulatedText: String,
                    ) = Unit

                    override fun onDeepAnalysisSuccess(
                        request: OnlineHazardDetectionService.DetailRequest,
                        fullText: String,
                    ) = Unit

                    override fun onDeepAnalysisFailure(
                        request: OnlineHazardDetectionService.DetailRequest,
                        message: String,
                    ) = Unit
                },
                requestGateway = gateway,
                scheduler = scheduler,
                elapsedRealtimeProvider = { nowElapsedMs },
                base64Encoder = { "encoded" },
                detectTimeoutMs = 1_500L,
                detectConcurrencyLimit = 5,
                infoLogger = { _ -> },
                warningLogger = { _ -> },
            )
        }

        fun advanceTimeBy(deltaMs: Long) {
            nowElapsedMs += deltaMs
            scheduler.runDueTasks()
        }
    }

    private class FakeScheduler(
        private val nowProvider: () -> Long,
    ) : OnlineHazardDetectionService.MainThreadScheduler {
        private data class ScheduledTask(
            val runnable: Runnable,
            val runAtMs: Long,
        )

        private val delayedTasks = mutableListOf<ScheduledTask>()

        override fun post(runnable: Runnable) {
            runnable.run()
        }

        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            delayedTasks.removeAll { it.runnable == runnable }
            delayedTasks += ScheduledTask(
                runnable = runnable,
                runAtMs = nowProvider() + delayMs,
            )
        }

        override fun removeCallbacks(runnable: Runnable) {
            delayedTasks.removeAll { it.runnable == runnable }
        }

        fun runDueTasks() {
            while (true) {
                val due = delayedTasks
                    .filter { it.runAtMs <= nowProvider() }
                    .minByOrNull { it.runAtMs }
                    ?: return
                delayedTasks.remove(due)
                due.runnable.run()
            }
        }
    }

    private class FakeRequestGateway : OnlineHazardDetectionService.RequestGateway {
        val detectCallbacks = mutableMapOf<Long, AiArSseService.DetectCallback>()
        val detectionHandles = mutableMapOf<Long, AiArSseService.RequestHandle>()
        var detailCallback: AiArSseService.DetailCallback? = null
        var detailHandle: AiArSseService.RequestHandle? = null
        val startedDetectionRequestIds = mutableListOf<Long>()
        val startedDetectionLanes = mutableListOf<OnlineHazardDetectionService.DetectionLane>()
        val startedDetectionJpegBytes = mutableListOf<ByteArray>()
        val startedDetailLanes = mutableListOf<OnlineHazardDetectionService.DetectionLane>()

        override fun identifyHazard(
            request: OnlineHazardDetectionService.DetectionRequest,
            callback: AiArSseService.DetectCallback,
        ): AiArSseService.RequestHandle {
            val requestId = request.requestId
            val lane = request.lane
            val handle = AiArSseService.RequestHandle(
                taskId = "detect-$requestId",
            )
            detectCallbacks[requestId] = callback
            detectionHandles[requestId] = handle
            startedDetectionRequestIds += requestId
            startedDetectionLanes += lane
            startedDetectionJpegBytes += request.jpegBytes
            return handle
        }

        override fun requestDeepAnalysis(
            request: OnlineHazardDetectionService.DetailRequest,
            onChunk: (String) -> Unit,
            callback: AiArSseService.DetailCallback,
        ): AiArSseService.RequestHandle {
            val handle = AiArSseService.RequestHandle(
                taskId = "detail",
            )
            detailCallback = callback
            detailHandle = handle
            startedDetailLanes += request.lane
            return handle
        }

        fun completeDetectionSuccess(
            requestId: Long,
            hasHazard: Boolean,
            fullText: String,
            labels: List<String> = emptyList(),
        ) {
            val callback = detectCallbacks[requestId] ?: return
            val activeHandle = detectionHandles[requestId] ?: return
            callback.onSuccess(activeHandle, hasHazard, fullText, labels)
        }
    }

    private class FakeLocalCoordinator : LocalTriggerDetectionService.CoordinatorGateway {
        override fun detect(
            assets: Any,
            bitmap: Any,
            traceLabel: String,
            callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
        ) {
            callback(LocalInferenceCoordinator.DetectionOutcome(false, null, "test"))
        }
    }
}
