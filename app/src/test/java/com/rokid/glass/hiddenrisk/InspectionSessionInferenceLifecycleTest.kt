package com.rokid.glass.hiddenrisk

import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class InspectionSessionInferenceLifecycleTest {

    @After
    fun tearDown() {
        InspectionSession.restoreCoordinatorForTest()
    }

    @Test
    fun releaseClearsStateOnlyAfterNativeReleaseCompletes() {
        val gateway = DeferredSessionCoordinator()
        InspectionSession.installCoordinatorForTest(gateway)

        InspectionSession.ensureModelLoaded(FakeAssets) {}
        gateway.completeEnsureLoaded(true)
        assertTrue(InspectionSession.isModelLoaded)

        InspectionSession.release {}
        assertTrue(InspectionSession.isModelLoaded)

        gateway.completeRelease()

        assertFalse(InspectionSession.isModelLoaded)
    }

    @Test
    fun failedEnsureReportsErrorAndCanRetry() {
        val gateway = FakeSessionCoordinator(results = ArrayDeque(listOf(false, true)))
        val results = mutableListOf<Boolean>()
        InspectionSession.installCoordinatorForTest(gateway)

        InspectionSession.ensureModelLoaded(FakeAssets) { results += it }
        InspectionSession.ensureModelLoaded(FakeAssets) { results += it }

        assertEquals(listOf(false, true), results)
        assertTrue(InspectionSession.isModelLoaded)
    }

    private class FakeSessionCoordinator(
        private val results: ArrayDeque<Boolean>,
    ) : InspectionSession.CoordinatorGateway {
        override fun ensureLoaded(
            assets: Any,
            callback: (LocalInferenceCoordinator.OperationResult) -> Unit,
        ) {
            val success = results.removeFirst()
            callback(LocalInferenceCoordinator.OperationResult(success, if (success) "" else "load failed"))
        }

        override fun detect(
            assets: Any,
            bitmap: Bitmap,
            traceLabel: String,
            callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
        ) = Unit

        override fun executeWithLoaded(
            assets: Any,
            callback: (LocalInferenceCoordinator.NativeEngine?, LocalInferenceCoordinator.OperationResult) -> Unit,
        ) = Unit

        override fun release(callback: (LocalInferenceCoordinator.OperationResult) -> Unit) {
            callback(LocalInferenceCoordinator.OperationResult(true))
        }
    }

    private class DeferredSessionCoordinator : InspectionSession.CoordinatorGateway {
        private var ensureCallback: ((LocalInferenceCoordinator.OperationResult) -> Unit)? = null
        private var releaseCallback: ((LocalInferenceCoordinator.OperationResult) -> Unit)? = null

        override fun ensureLoaded(
            assets: Any,
            callback: (LocalInferenceCoordinator.OperationResult) -> Unit,
        ) {
            ensureCallback = callback
        }

        fun completeEnsureLoaded(success: Boolean) {
            ensureCallback?.invoke(LocalInferenceCoordinator.OperationResult(success))
        }

        override fun detect(
            assets: Any,
            bitmap: Bitmap,
            traceLabel: String,
            callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
        ) = Unit

        override fun executeWithLoaded(
            assets: Any,
            callback: (LocalInferenceCoordinator.NativeEngine?, LocalInferenceCoordinator.OperationResult) -> Unit,
        ) = Unit

        override fun release(callback: (LocalInferenceCoordinator.OperationResult) -> Unit) {
            releaseCallback = callback
        }

        fun completeRelease() {
            releaseCallback?.invoke(LocalInferenceCoordinator.OperationResult(true))
        }
    }

    private object FakeAssets
}
