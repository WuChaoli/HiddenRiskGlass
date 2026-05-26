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
    fun `sdk zoom level one maps to lowest zoom`() {
        assertEquals(1, RokidFrameSource.sdkZoomLevelFor(1.0f))
    }

    @Test
    fun `sdk zoom level thresholds stay aligned with current mapping`() {
        assertEquals(1, RokidFrameSource.sdkZoomLevelFor(1.0f))
        assertEquals(2, RokidFrameSource.sdkZoomLevelFor(1.9f))
        assertEquals(3, RokidFrameSource.sdkZoomLevelFor(2.5f))
    }

    @Test
    fun `validated nv21 square crop matches verified field of view`() {
        val crop = SharedCameraViewportPolicy.calculateValidatedNv21SquareCropRect(1920, 1080)

        assertEquals(420, crop.left)
        assertEquals(0, crop.top)
        assertEquals(1500, crop.right)
        assertEquals(1080, crop.bottom)
    }

    @Test
    fun `validated nv21 square crop remains centered and even aligned`() {
        val portraitCrop = SharedCameraViewportPolicy.calculateValidatedNv21SquareCropRect(720, 1041)
        val emptyCrop = SharedCameraViewportPolicy.calculateValidatedNv21SquareCropRect(0, 1080)

        assertEquals(0, portraitCrop.left)
        assertEquals(160, portraitCrop.top)
        assertEquals(720, portraitCrop.right)
        assertEquals(880, portraitCrop.bottom)
        assertEquals(0, emptyCrop.left)
        assertEquals(0, emptyCrop.top)
        assertEquals(0, emptyCrop.right)
        assertEquals(0, emptyCrop.bottom)
    }

    @Test
    fun `stale helper callback is rejected after generation switch`() {
        assertEquals(false, RokidFrameSource.isHelperCallbackStale(activeGeneration = 7L, callbackGeneration = 7L))
        assertEquals(true, RokidFrameSource.isHelperCallbackStale(activeGeneration = 8L, callbackGeneration = 7L))
        assertEquals(true, RokidFrameSource.isHelperCallbackStale(activeGeneration = 0L, callbackGeneration = 7L))
    }

    @Test
    fun `restart reuses existing helper only while sdk is ready`() {
        assertEquals(
            RokidFrameSource.FrameRestartPath.REUSE_EXISTING_HELPER,
            RokidFrameSource.chooseFrameRestartPath(helperExists = true, sdkReady = true),
        )
        assertEquals(
            RokidFrameSource.FrameRestartPath.REBUILD_HELPER,
            RokidFrameSource.chooseFrameRestartPath(helperExists = false, sdkReady = true),
        )
        assertEquals(
            RokidFrameSource.FrameRestartPath.REBUILD_HELPER,
            RokidFrameSource.chooseFrameRestartPath(helperExists = true, sdkReady = false),
        )
    }

    @Test
    fun `portrait surface keeps landscape frame square roi proportions`() {
        val mapping = RokidFrameSource.mapFrameCropToSurfaceTexture(
            surfaceWidth = 1080,
            surfaceHeight = 1920,
            frameWidth = 1920,
            frameHeight = 1080,
            frameCrop = RokidFrameSource.NormalizedCropRect(
                left = 420f / 1920f,
                top = 0f,
                width = 1080f / 1920f,
                height = 1f,
            ),
            matrixSwapped = false,
        )!!

        assertEquals("frame_roi", mapping.mode)
        assertEquals(0.21875f, mapping.textureCrop.left, 0.0001f)
        assertEquals(0f, mapping.textureCrop.top, 0.0001f)
        assertEquals(0.5625f, mapping.textureCrop.width, 0.0001f)
        assertEquals(1f, mapping.textureCrop.height, 0.0001f)
    }

    @Test
    fun `same orientation surface uses direct roi mapping`() {
        val expected = RokidFrameSource.NormalizedCropRect(0.2f, 0.1f, 0.5f, 0.5f)
        val mapping = RokidFrameSource.mapFrameCropToSurfaceTexture(
            surfaceWidth = 1920,
            surfaceHeight = 1080,
            frameWidth = 1920,
            frameHeight = 1080,
            frameCrop = expected,
            matrixSwapped = false,
        )!!

        assertEquals("direct", mapping.mode)
        assertEquals(expected, mapping.textureCrop)
    }

    @Test
    fun `matrix swapped orientation does not transpose roi twice`() {
        val expected = RokidFrameSource.NormalizedCropRect(0.1f, 0.2f, 0.7f, 0.6f)
        val mapping = RokidFrameSource.mapFrameCropToSurfaceTexture(
            surfaceWidth = 1080,
            surfaceHeight = 1920,
            frameWidth = 1920,
            frameHeight = 1080,
            frameCrop = expected,
            matrixSwapped = true,
        )!!

        assertEquals("direct", mapping.mode)
        assertEquals(expected, mapping.textureCrop)
    }

    @Test
    fun `frame roi mapping does not alter offset proportions`() {
        val mapping = RokidFrameSource.mapFrameCropToSurfaceTexture(
            surfaceWidth = 1080,
            surfaceHeight = 1920,
            frameWidth = 1920,
            frameHeight = 1080,
            frameCrop = RokidFrameSource.NormalizedCropRect(0.15f, 0.25f, 0.5f, 0.5f),
            matrixSwapped = false,
        )!!

        assertEquals("frame_roi", mapping.mode)
        assertEquals(0.15f, mapping.textureCrop.left, 0.0001f)
        assertEquals(0.25f, mapping.textureCrop.top, 0.0001f)
        assertEquals(0.5f, mapping.textureCrop.width, 0.0001f)
        assertEquals(0.5f, mapping.textureCrop.height, 0.0001f)
    }

    @Test
    fun `loading handoff to enterprise then ai keeps latest owner`() {
        val stateMachine = InspectionCameraCoordinator.StateMachine()

        val loadingAcquire = stateMachine.beginAcquire(
            owner = CameraOwner.LOADING,
            readyNow = false,
            needPreview = false,
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
            needPreview = true,
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
            needPreview = false,
        )
        assertEquals(CameraOwner.AI_INSPECTION, aiAcquire.owner)
        assertEquals(aiAcquire.generation, stateMachine.snapshot().generation)
        assertEquals(CameraSessionState.READY_NO_PREVIEW, stateMachine.snapshot().state)
    }
}
