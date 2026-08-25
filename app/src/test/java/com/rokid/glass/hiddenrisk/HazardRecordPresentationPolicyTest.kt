package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Test

class HazardRecordPresentationPolicyTest {
    @Test
    fun `captured image becomes result background while v2 request is running`() {
        assertEquals(
            HazardRecordPresentation.RESULT_BACKGROUND,
            HazardRecordPresentationPolicy.afterCapture(),
        )
    }

    @Test
    fun `empty v2 result returns to idle without showing a result card`() {
        assertEquals(
            HazardRecordPresentation.IDLE,
            HazardRecordPresentationPolicy.afterResponse(hasHazards = false),
        )
        assertEquals(
            HazardRecordPresentation.RESULT_WITH_HAZARDS,
            HazardRecordPresentationPolicy.afterResponse(hasHazards = true),
        )
    }
}
