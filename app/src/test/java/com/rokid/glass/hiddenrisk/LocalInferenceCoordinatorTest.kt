package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class LocalInferenceCoordinatorTest {

    @Test
    fun twoDetectionsLoadOnceAndRunInSubmissionOrder() {
        val executor = QueuedTaskExecutor()
        val engine = FakeNativeEngine()
        val coordinator = LocalInferenceCoordinator(executor) { engine }
        val results = mutableListOf<Boolean>()

        coordinator.detect(FakeAssets, BitmapToken("first")) { results += it.success }
        coordinator.detect(FakeAssets, BitmapToken("second")) { results += it.success }
        executor.runAll()

        assertEquals(1, engine.loadCount)
        assertEquals(
            listOf("load", "detect:first", "stats", "detect:second", "stats"),
            engine.calls,
        )
        assertEquals(listOf(true, true), results)
    }

    @Test
    fun failedLoadCanRetryOnNextRequest() {
        val engine = FakeNativeEngine(loadResults = ArrayDeque(listOf(false, true)))
        val coordinator = LocalInferenceCoordinator(ImmediateTaskExecutor) { engine }
        val results = mutableListOf<Boolean>()

        coordinator.ensureLoaded(FakeAssets) { results += it.success }
        coordinator.ensureLoaded(FakeAssets) { results += it.success }

        assertEquals(listOf(false, true), results)
        assertEquals(2, engine.loadCount)
    }

    @Test
    fun releaseRunsAfterQueuedDetectionAndNextDetectionReloads() {
        val executor = QueuedTaskExecutor()
        val engine = FakeNativeEngine()
        val coordinator = LocalInferenceCoordinator(executor) { engine }

        coordinator.detect(FakeAssets, BitmapToken("before")) {}
        coordinator.release {}
        coordinator.detect(FakeAssets, BitmapToken("after")) {}
        executor.runAll()

        assertEquals(
            listOf("load", "detect:before", "stats", "release", "load", "detect:after", "stats"),
            engine.calls,
        )
    }

    @Test
    fun detectReportsNativeFailureAndStatsFromSameTask() {
        val engine = FakeNativeEngine(detectResult = false)
        val coordinator = LocalInferenceCoordinator(ImmediateTaskExecutor) { engine }
        var outcome: LocalInferenceCoordinator.DetectionOutcome? = null

        coordinator.detect(FakeAssets, BitmapToken("failed")) { outcome = it }

        assertFalse(outcome!!.success)
        assertEquals("native detect failed", outcome!!.errorMessage)
        assertTrue(outcome!!.stats === engine.stats)
    }

    private object FakeAssets

    private data class BitmapToken(val name: String)

    private object ImmediateTaskExecutor : LocalInferenceCoordinator.TaskExecutor {
        override fun execute(task: () -> Unit) = task()
    }

    private class QueuedTaskExecutor : LocalInferenceCoordinator.TaskExecutor {
        private val tasks = ArrayDeque<() -> Unit>()

        override fun execute(task: () -> Unit) {
            tasks.addLast(task)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().invoke()
            }
        }
    }

    private class FakeNativeEngine(
        private val loadResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true)),
        private val detectResult: Boolean = true,
    ) : LocalInferenceCoordinator.NativeEngine {
        val calls = mutableListOf<String>()
        val stats = emptyStats()
        var loadCount = 0

        override fun load(assets: Any): Boolean {
            calls += "load"
            loadCount += 1
            return if (loadResults.size > 1) loadResults.removeFirst() else loadResults.first()
        }

        override fun detect(bitmap: Any): Boolean {
            calls += "detect:${(bitmap as BitmapToken).name}"
            return detectResult
        }

        override fun latestStats(): NativeInferenceStats {
            calls += "stats"
            return stats
        }

        override fun release() {
            calls += "release"
        }

        override fun errorMessage(): String = "native detect failed"
    }

    companion object {
        private fun emptyStats(): NativeInferenceStats {
            return NativeInferenceStats(
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
            )
        }
    }
}
