package com.rokid.glass.hiddenrisk

import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionCameraCoordinatorStateMachineTest {

    @Test
    fun beginRelease_ignoresOldOwnerAfterTransfer() {
        val stateMachine = InspectionCameraCoordinator.StateMachine()

        val aiSnapshot = stateMachine.beginAcquire(
            CameraOwner.AI_INSPECTION,
            readyNow = true,
            needPreview = true,
        )
        assertTrue(
            stateMachine.finishReady(
                owner = CameraOwner.AI_INSPECTION,
                generation = aiSnapshot.generation,
                needPreview = true,
            ),
        )

        val deviceGuideSnapshot = stateMachine.beginAcquire(
            CameraOwner.DEVICE_GUIDE,
            readyNow = true,
            needPreview = true,
        )
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

        val deviceGuideSnapshot = stateMachine.beginAcquire(
            CameraOwner.DEVICE_GUIDE,
            readyNow = true,
            needPreview = true,
        )
        assertTrue(
            stateMachine.finishReady(
                owner = CameraOwner.DEVICE_GUIDE,
                generation = deviceGuideSnapshot.generation,
                needPreview = true,
            ),
        )

        val aiSnapshot = stateMachine.beginAcquire(
            CameraOwner.AI_INSPECTION,
            readyNow = true,
            needPreview = true,
        )
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
                needPreview = true,
            ),
        )
        val snapshot = stateMachine.snapshot()
        assertEquals(CameraOwner.AI_INSPECTION, snapshot.owner)
        assertEquals(CameraSessionState.READY_WITH_PREVIEW, snapshot.state)
        assertEquals(aiSnapshot.generation, snapshot.generation)
    }

    // HAZARD_RECORD -> DEVICE_GUIDE 竞争：新 owner acquire 后，旧 owner release 应被忽略
    @Test
    fun hazardRecordRelease_ignoredAfterDeviceGuideAcquire() {
        val stateMachine = InspectionCameraCoordinator.StateMachine()

        // 隐患录入页获取 owner
        val hazardSnapshot = stateMachine.beginAcquire(
            CameraOwner.HAZARD_RECORD,
            readyNow = true,
            needPreview = false,
        )
        assertTrue(
            stateMachine.finishReady(
                owner = CameraOwner.HAZARD_RECORD,
                generation = hazardSnapshot.generation,
                needPreview = false,
            ),
        )

        // 设备指引页抢占 owner
        val deviceGuideSnapshot = stateMachine.beginAcquire(
            CameraOwner.DEVICE_GUIDE,
            readyNow = true,
            needPreview = true,
        )
        assertTrue(
            stateMachine.finishReady(
                owner = CameraOwner.DEVICE_GUIDE,
                generation = deviceGuideSnapshot.generation,
                needPreview = true,
            ),
        )

        // 隐患录入页 onPause/onDestroy 的 release 应被忽略
        val releaseResult = stateMachine.beginRelease(CameraOwner.HAZARD_RECORD)
        assertNull("旧 owner HAZARD_RECORD 的 release 应被忽略", releaseResult)

        // 验证状态未被回退到 IDLE，仍由 DEVICE_GUIDE 持有
        val snapshot = stateMachine.snapshot()
        assertEquals(CameraOwner.DEVICE_GUIDE, snapshot.owner)
        assertEquals(CameraSessionState.READY_WITH_PREVIEW, snapshot.state)
        assertEquals(deviceGuideSnapshot.generation, snapshot.generation)
    }

    // 同 owner 预览切换：needPreview=true -> false -> true
    @Test
    fun previewToggle_sameOwner_needPreviewTrueFalseTrue() {
        val stateMachine = InspectionCameraCoordinator.StateMachine()

        // 初始获取，needPreview=true
        val acquire1 = stateMachine.beginAcquire(
            CameraOwner.DEVICE_GUIDE,
            readyNow = false,
            needPreview = true,
        )
        assertNotNull(acquire1)
        assertTrue(
            stateMachine.finishReady(
                owner = CameraOwner.DEVICE_GUIDE,
                generation = acquire1.generation,
                needPreview = true,
            ),
        )
        assertEquals(CameraSessionState.READY_WITH_PREVIEW, stateMachine.snapshot().state)

        // 关闭预览，needPreview=false
        val update1 = stateMachine.beginPreviewUpdate(
            CameraOwner.DEVICE_GUIDE,
            readyNow = true,
            needPreview = false,
        )
        assertNotNull("同 owner 关闭预览应成功", update1)
        assertTrue(
            stateMachine.finishReady(
                owner = CameraOwner.DEVICE_GUIDE,
                generation = update1!!.generation,
                needPreview = false,
            ),
        )
        assertEquals(CameraSessionState.READY_NO_PREVIEW, stateMachine.snapshot().state)

        // 重新打开预览，needPreview=true
        val update2 = stateMachine.beginPreviewUpdate(
            CameraOwner.DEVICE_GUIDE,
            readyNow = true,
            needPreview = true,
        )
        assertNotNull("同 owner 重新打开预览应成功", update2)
        assertTrue(
            stateMachine.finishReady(
                owner = CameraOwner.DEVICE_GUIDE,
                generation = update2!!.generation,
                needPreview = true,
            ),
        )
        val snapshot = stateMachine.snapshot()
        assertEquals(CameraOwner.DEVICE_GUIDE, snapshot.owner)
        assertEquals(CameraSessionState.READY_WITH_PREVIEW, snapshot.state)
        assertEquals(update2!!.generation, snapshot.generation)
    }
}
