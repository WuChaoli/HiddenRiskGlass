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
}
