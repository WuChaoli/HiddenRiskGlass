package com.rokid.glass.camera

import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RokidFrameSourceTest {

    @Test
    fun `shared frame stream zoom stays at two x`() {
        assertEquals(2.0f, RokidFrameSource.SHARED_FRAME_STREAM_ZOOM_RATIO, 0.0f)
    }

    @Test
    fun `shared two x zoom maps to sdk level two`() {
        assertEquals(2, RokidFrameSource.sdkZoomLevelFor(RokidFrameSource.SHARED_FRAME_STREAM_ZOOM_RATIO))
    }

    @Test
    fun `sdk zoom level thresholds stay aligned with current mapping`() {
        assertEquals(1, RokidFrameSource.sdkZoomLevelFor(1.0f))
        assertEquals(2, RokidFrameSource.sdkZoomLevelFor(1.9f))
        assertEquals(3, RokidFrameSource.sdkZoomLevelFor(2.5f))
    }

    @Test
    fun `stale helper callback is rejected after generation switch`() {
        assertEquals(false, RokidFrameSource.isHelperCallbackStale(activeGeneration = 7L, callbackGeneration = 7L))
        assertEquals(true, RokidFrameSource.isHelperCallbackStale(activeGeneration = 8L, callbackGeneration = 7L))
        assertEquals(true, RokidFrameSource.isHelperCallbackStale(activeGeneration = 0L, callbackGeneration = 7L))
    }

    @Test
    fun `loading handoff to enterprise then ai keeps latest owner`() {
        val stateMachine = InspectionCameraCoordinator.StateMachine()

        val loadingAcquire = stateMachine.beginAcquire(
            owner = CameraOwner.LOADING,
            readyNow = false,
        )
        assertTrue(
            stateMachine.finishReady(
                owner = CameraOwner.LOADING,
                generation = loadingAcquire.generation,
                needPreview = false,
            ),
        )
        assertEquals(CameraOwner.LOADING, stateMachine.snapshot().owner)
        assertEquals(CameraSessionState.READY_NO_PREVIEW, stateMachine.snapshot().state)

        val enterpriseAcquire = stateMachine.beginAcquire(
            owner = CameraOwner.ENTERPRISE_QR_SCAN,
            readyNow = true,
        )
        assertEquals(CameraOwner.ENTERPRISE_QR_SCAN, enterpriseAcquire.owner)
        assertEquals(CameraSessionState.READY_NO_PREVIEW, enterpriseAcquire.state)

        val loadingRelease = stateMachine.beginRelease(CameraOwner.LOADING)
        assertNull(loadingRelease)
        assertEquals(CameraOwner.ENTERPRISE_QR_SCAN, stateMachine.snapshot().owner)
        assertEquals(enterpriseAcquire.generation, stateMachine.snapshot().generation)

        assertTrue(
            stateMachine.finishReady(
                owner = CameraOwner.ENTERPRISE_QR_SCAN,
                generation = enterpriseAcquire.generation,
                needPreview = true,
            ),
        )
        assertEquals(CameraSessionState.READY_WITH_PREVIEW, stateMachine.snapshot().state)

        val aiAcquire = stateMachine.beginAcquire(
            owner = CameraOwner.AI_INSPECTION,
            readyNow = true,
        )
        assertEquals(CameraOwner.AI_INSPECTION, aiAcquire.owner)
        assertEquals(aiAcquire.generation, stateMachine.snapshot().generation)
        assertEquals(CameraSessionState.READY_NO_PREVIEW, stateMachine.snapshot().state)
    }
}
