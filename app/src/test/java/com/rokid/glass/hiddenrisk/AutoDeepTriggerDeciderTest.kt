package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDeepTriggerDeciderTest {

    @Test
    fun `box smaller than one eighth of visible screen does not trigger deep`() {
        val detection = detection(right = 239f, bottom = 160f)

        assertFalse(AutoDeepTriggerDecider.shouldTrigger(listOf(detection), FrameSize(480, 640)))
    }

    @Test
    fun `box exactly one eighth of visible screen does not trigger deep`() {
        val detection = detection(right = 240f, bottom = 160f)

        assertFalse(AutoDeepTriggerDecider.shouldTrigger(listOf(detection), FrameSize(480, 640)))
    }

    @Test
    fun `box larger than one eighth of visible screen triggers deep`() {
        val detection = detection(right = 241f, bottom = 160f)

        assertTrue(AutoDeepTriggerDecider.shouldTrigger(listOf(detection), FrameSize(480, 640)))
    }

    @Test
    fun `any qualifying visible box triggers deep`() {
        val detections = listOf(
            detection(right = 10f, bottom = 10f),
            detection(left = 80f, top = 80f, right = 320f, bottom = 241f),
        )

        assertTrue(AutoDeepTriggerDecider.shouldTrigger(detections, FrameSize(480, 640)))
    }

    @Test
    fun `empty detections do not trigger deep`() {
        assertFalse(AutoDeepTriggerDecider.shouldTrigger(emptyList(), FrameSize(480, 640)))
    }

    @Test
    fun `qualifying labels exclude cooling labels without changing source detections`() {
        val detections = listOf(
            detection(label = "燃气灶", right = 241f, bottom = 160f),
            detection(label = "热水器", left = 80f, top = 80f, right = 321f, bottom = 241f),
        )

        val labels = AutoDeepTriggerDecider.qualifyingLabels(
            visibleDetections = detections,
            screenSize = FrameSize(480, 640),
            isCooling = { it == "燃气灶" },
        )

        assertEquals(listOf("热水器"), labels)
        assertEquals(2, detections.size)
    }

    @Test
    fun `qualifying labels are trimmed and deduplicated`() {
        val detections = listOf(
            detection(label = " 燃气灶 ", right = 241f, bottom = 160f),
            detection(label = "燃气灶", left = 80f, top = 80f, right = 321f, bottom = 241f),
        )

        assertEquals(
            listOf("燃气灶"),
            AutoDeepTriggerDecider.qualifyingLabels(detections, FrameSize(480, 640)) { false },
        )
    }

    private fun detection(
        label: String = "test",
        left: Float = 0f,
        top: Float = 0f,
        right: Float,
        bottom: Float,
    ) = AlignmentDetection(label, 0.9f, left, top, right, bottom)
}
