package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullFrameAutoRequestPolicyTest {

    @Test
    fun missingPlaceCodeBlocksAutoRequest() {
        assertFalse(FullFrameAutoRequestPolicy.canRequest(placeCode = "  "))
    }

    @Test
    fun configuredPlaceCodeAllowsAutoRequest() {
        assertTrue(FullFrameAutoRequestPolicy.canRequest(placeCode = "XFAQ-JXCS-001"))
    }
}
