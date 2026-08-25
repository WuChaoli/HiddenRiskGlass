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

    @Test
    fun `hazard v2 result plays alert before presenting result`() {
        assertEquals(
            HazardRecordAudioCue.HAS_HAZARD,
            HazardRecordPresentationPolicy.audioCueAfterResponse(hasHazards = true),
        )
        assertEquals(
            HazardRecordAudioCue.NONE,
            HazardRecordPresentationPolicy.audioCueAfterResponse(hasHazards = false),
        )
    }

    @Test
    fun `accepted save returns immediately to photo waiting page`() {
        assertEquals(
            HazardRecordPresentation.IDLE,
            HazardRecordPresentationPolicy.afterSaveAccepted(),
        )
    }
}
