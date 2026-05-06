package com.rokid.glass.hiddenrisk

import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionCameraCoordinatorStateMachineTest {

    @Test
    fun beginRelease_ignoresOldOwnerAfterTransfer() {
        val stateMachine = InspectionCameraCoordinator.StateMachine()

        val aiSnapshot = stateMachine.beginAcquire(CameraOwner.AI_INSPECTION, readyNow = true)
        assertTrue(
            stateMachine.finishReady(
                owner = CameraOwner.AI_INSPECTION,
                generation = aiSnapshot.generation,
                needPreview = true,
            ),
        )

        val deviceGuideSnapshot = stateMachine.beginAcquire(CameraOwner.DEVICE_GUIDE, readyNow = true)
        assertTrue(
            stateMachine.finishReady(
                owner = CameraOwner.DEVICE_GUIDE,
                generation = deviceGuideSnapshot.generation,
                needPreview = true,
            ),
        )

        assertNull(stateMachine.beginRelease(CameraOwner.AI_INSPECTION))
        val snapshot = stateMachine.snapshot()
        assertEquals(CameraOwner.DEVICE_GUIDE, snapshot.owner)
        assertEquals(CameraSessionState.READY_WITH_PREVIEW, snapshot.state)
        assertEquals(deviceGuideSnapshot.generation, snapshot.generation)
    }

    @Test
    fun beginPreviewUpdate_ignoresOldOwnerAfterTransfer() {
        val stateMachine = InspectionCameraCoordinator.StateMachine()

        val deviceGuideSnapshot = stateMachine.beginAcquire(CameraOwner.DEVICE_GUIDE, readyNow = true)
        assertTrue(
            stateMachine.finishReady(
                owner = CameraOwner.DEVICE_GUIDE,
                generation = deviceGuideSnapshot.generation,
                needPreview = true,
            ),
        )

        val aiSnapshot = stateMachine.beginAcquire(CameraOwner.AI_INSPECTION, readyNow = true)
        assertTrue(
            stateMachine.finishReady(
                owner = CameraOwner.AI_INSPECTION,
                generation = aiSnapshot.generation,
                needPreview = true,
            ),
        )

        assertNull(
            stateMachine.beginPreviewUpdate(
                owner = CameraOwner.DEVICE_GUIDE,
                readyNow = true,
            ),
        )
        val snapshot = stateMachine.snapshot()
        assertEquals(CameraOwner.AI_INSPECTION, snapshot.owner)
        assertEquals(CameraSessionState.READY_WITH_PREVIEW, snapshot.state)
        assertEquals(aiSnapshot.generation, snapshot.generation)
    }
}
