package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardDetailDisplayModelTest {
    @Test
    fun from_usesPlaceholdersForBlankFields() {
        val model = HazardDetailDisplayModel.from(hazard())

        assertEquals("燃气灶", model.label)
        assertEquals("--", model.hazardCode)
        assertEquals("--", model.level)
        assertEquals("暂无", model.description)
        assertEquals("暂无", model.advice)
        assertEquals("暂无", model.lawBasis)
    }

    @Test
    fun pageIndicator_onlyShowsForMultipleHazards() {
        assertFalse(HazardDetailDisplayModel.shouldShowPageIndicator(1))
        assertTrue(HazardDetailDisplayModel.shouldShowPageIndicator(2))
    }

    private fun hazard() = DeepV2PresentationHazard(
        labelId = "1",
        label = "燃气灶",
        description = "",
        level = "",
        lawBasis = "",
        advice = "",
        hazardCode = "",
        sourceIndex = 0,
    )
}
