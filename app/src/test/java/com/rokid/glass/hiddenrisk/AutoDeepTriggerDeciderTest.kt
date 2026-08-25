package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDeepTriggerDeciderTest {

    @Test
    fun `box smaller than one eighth of visible screen does not trigger deep`() {
        val detection = detection(right = 239f, bottom = 160f)

        assertFalse(AutoDeepTriggerDecider.shouldTrigger(listOf(detection), FrameSize(480, 640)))
    }

    @Test
    fun `box exactly one eighth of visible screen triggers deep`() {
        val detection = detection(right = 240f, bottom = 160f)

        assertTrue(AutoDeepTriggerDecider.shouldTrigger(listOf(detection), FrameSize(480, 640)))
    }

    @Test
    fun `any qualifying visible box triggers deep`() {
        val detections = listOf(
            detection(right = 10f, bottom = 10f),
            detection(left = 80f, top = 80f, right = 320f, bottom = 240f),
        )

        assertTrue(AutoDeepTriggerDecider.shouldTrigger(detections, FrameSize(480, 640)))
    }

    @Test
    fun `empty detections do not trigger deep`() {
        assertFalse(AutoDeepTriggerDecider.shouldTrigger(emptyList(), FrameSize(480, 640)))
    }

    private fun detection(
        left: Float = 0f,
        top: Float = 0f,
        right: Float,
        bottom: Float,
    ) = AlignmentDetection("test", 0.9f, left, top, right, bottom)
}
