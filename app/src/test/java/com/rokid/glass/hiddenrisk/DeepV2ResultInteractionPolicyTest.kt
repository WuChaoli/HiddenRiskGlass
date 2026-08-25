package com.rokid.glass.hiddenrisk

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeepV2ResultInteractionPolicyTest {
    @Test
    fun `physical forward swipe maps to forward navigation`() {
        assertEquals(
            DeepV2NavigationDirection.FORWARD,
            DeepV2ResultInteractionPolicy.directionForTouchKey(KeyEvent.KEYCODE_DPAD_RIGHT),
        )
        assertEquals(
            DeepV2NavigationDirection.BACKWARD,
            DeepV2ResultInteractionPolicy.directionForTouchKey(KeyEvent.KEYCODE_DPAD_LEFT),
        )
    }

    @Test
    fun `hazard card title contains label without hazard level`() {
        val title = DeepV2ResultInteractionPolicy.cardTitle("燃气灶")

        assertEquals("燃气灶", title)
        assertFalse(title.contains("一般隐患"))
    }

    @Test
    fun `bbox animation runs only between two different real boxes`() {
        assertEquals(true, DeepV2ResultInteractionPolicy.shouldAnimateBoxChange("det-1", "det-2"))
        assertEquals(false, DeepV2ResultInteractionPolicy.shouldAnimateBoxChange("det-1", "det-1"))
        assertEquals(false, DeepV2ResultInteractionPolicy.shouldAnimateBoxChange("det-1", null))
        assertEquals(false, DeepV2ResultInteractionPolicy.shouldAnimateBoxChange(null, "det-1"))
        assertEquals(false, DeepV2ResultInteractionPolicy.shouldAnimateBoxChange(null, null))
    }

    @Test
    fun `detail waits for initial bbox focus animation`() {
        assertEquals(
            DeepV2FocusTransition.FOCUS_THEN_SHOW_DETAIL,
            DeepV2ResultInteractionPolicy.focusTransition(null, "det-1"),
        )
    }

    @Test
    fun `switching focus hides detail until bbox transition completes`() {
        assertEquals(
            DeepV2FocusTransition.SWITCH_BOX_THEN_SHOW_DETAIL,
            DeepV2ResultInteractionPolicy.focusTransition("det-1", "det-2"),
        )
    }

    @Test
    fun `hazard without bbox waits for previous bbox to shrink`() {
        assertEquals(
            DeepV2FocusTransition.DEFOCUS_THEN_SHOW_DETAIL,
            DeepV2ResultInteractionPolicy.focusTransition("det-1", null),
        )
        assertEquals(
            DeepV2FocusTransition.SHOW_DETAIL_IMMEDIATELY,
            DeepV2ResultInteractionPolicy.focusTransition(null, null),
        )
    }
}
