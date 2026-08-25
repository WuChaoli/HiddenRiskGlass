package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignedDeepImageCropPlannerTest {
    @Test
    fun `calibrated crop matches visible three by four viewport`() {
        val crop = AlignedDeepImageCropPlanner.plan(
            sourceSize = FrameSize(1200, 1600),
            calibration = FullFrameOverlayCalibrationState().calibration,
        )

        assertTrue(crop.left > 0)
        assertTrue(crop.top > 0)
        assertTrue(crop.right <= 1200)
        assertTrue(crop.bottom <= 1600)
        assertEquals(crop.width * 4, crop.height * 3)
    }
}
