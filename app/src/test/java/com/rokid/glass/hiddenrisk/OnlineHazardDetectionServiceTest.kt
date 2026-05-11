package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

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
    fun submitDetection_sceneLaneIsDisabled() {
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
        assertEquals(-1, env.gateway.detectionHandles[23L]?.ctype)
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
                encodeExecutor = ImmediateExecutorService(),
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
        val startedDetectionRequestIds = mutableListOf<Long>()
        val startedDetectionLanes = mutableListOf<OnlineHazardDetectionService.DetectionLane>()

        override fun identifyHazard(
            request: OnlineHazardDetectionService.DetectionRequest,
            base64Image: String,
            callback: AiArSseService.DetectCallback,
        ): AiArSseService.RequestHandle {
            val requestId = request.requestId
            val lane = request.lane
            val handle = AiArSseService.RequestHandle(
                taskId = "detect-$requestId",
                ctype = lane.ctype,
            )
            detectCallbacks[requestId] = callback
            detectionHandles[requestId] = handle
            startedDetectionRequestIds += requestId
            startedDetectionLanes += lane
            return handle
        }

        override fun requestDeepAnalysis(
            request: OnlineHazardDetectionService.DetailRequest,
            base64Image: String,
            onChunk: (String) -> Unit,
            callback: AiArSseService.DetailCallback,
        ): AiArSseService.RequestHandle {
            val handle = AiArSseService.RequestHandle(
                taskId = "detail",
                ctype = 0,
            )
            detailCallback = callback
            return handle
        }

        fun completeDetectionSuccess(
            requestId: Long,
            hasHazard: Boolean,
            fullText: String,
        ) {
            val callback = detectCallbacks[requestId] ?: return
            val activeHandle = detectionHandles[requestId] ?: return
            callback.onSuccess(activeHandle, hasHazard, fullText)
        }
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
