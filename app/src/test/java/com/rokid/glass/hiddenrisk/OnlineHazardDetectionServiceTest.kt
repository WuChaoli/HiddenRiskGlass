package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class OnlineHazardDetectionServiceTest {

    @Test
    fun submitDetection_rejectsSecondRequestWhileActive() {
        val env = TestEnv()
        val service = env.createService()

        val first = detectionRequest(requestId = 1L)
        val second = detectionRequest(requestId = 2L)

        service.submitDetection(first)
        service.submitDetection(second)

        assertEquals(listOf(1L), env.gateway.startedDetectionRequestIds)
        assertEquals(listOf("drop:2:busy"), env.callbackEvents)
    }

    @Test
    fun submitDetection_timesOutAfterThreeSecondsAndCancelsHandle() {
        val env = TestEnv()
        val service = env.createService()

        val request = detectionRequest(requestId = 7L)
        service.submitDetection(request)

        env.advanceTimeBy(2999L)
        assertTrue(env.callbackEvents.isEmpty())
        assertFalse(env.gateway.lastDetectionHandle?.isCanceled() ?: true)

        env.advanceTimeBy(1L)

        assertEquals(listOf("drop:7:timeout"), env.callbackEvents)
        assertTrue(env.gateway.lastDetectionHandle?.isCanceled() ?: false)
    }

    @Test
    fun submitDetection_allowsNextRequestAfterSuccess() {
        val env = TestEnv()
        val service = env.createService()

        val first = detectionRequest(requestId = 11L)
        val second = detectionRequest(requestId = 12L)

        service.submitDetection(first)
        env.gateway.completeDetectionSuccess(hasHazard = false, fullText = "否")
        service.submitDetection(second)

        assertEquals(listOf(11L, 12L), env.gateway.startedDetectionRequestIds)
        assertEquals(listOf("result:11:false"), env.callbackEvents)
    }

    @Test
    fun cancelAll_preventsOldDetectionCallbackDelivery() {
        val env = TestEnv()
        val service = env.createService()

        val request = detectionRequest(requestId = 19L)
        service.submitDetection(request)
        val staleHandle = env.gateway.lastDetectionHandle

        service.cancelAll()
        env.gateway.completeDetectionSuccess(
            handle = staleHandle,
            hasHazard = true,
            fullText = "是",
        )

        assertTrue(env.callbackEvents.isEmpty())
        assertTrue(staleHandle?.isCanceled() ?: false)
    }

    private fun detectionRequest(requestId: Long): OnlineHazardDetectionService.DetectionRequest {
        return OnlineHazardDetectionService.DetectionRequest(
            epoch = 1L,
            requestId = requestId,
            jpegBytes = byteArrayOf(1, 2, 3),
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
        var detectCallback: AiArSseService.DetectCallback? = null
        var lastDetectionHandle: AiArSseService.RequestHandle? = null
        var detailCallback: AiArSseService.DetailCallback? = null
        val startedDetectionRequestIds = mutableListOf<Long>()

        override fun identifyItemHazard(
            request: OnlineHazardDetectionService.DetectionRequest,
            base64Image: String,
            callback: AiArSseService.DetectCallback,
        ): AiArSseService.RequestHandle {
            val requestId = request.requestId
            val handle = AiArSseService.RequestHandle(
                taskId = "detect-$requestId",
                ctype = 1,
            )
            detectCallback = callback
            lastDetectionHandle = handle
            startedDetectionRequestIds += requestId
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
            handle: AiArSseService.RequestHandle? = lastDetectionHandle,
            hasHazard: Boolean,
            fullText: String,
        ) {
            val callback = detectCallback ?: return
            val activeHandle = handle ?: return
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
