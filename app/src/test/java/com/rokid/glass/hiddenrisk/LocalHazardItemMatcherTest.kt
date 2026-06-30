package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalHazardItemMatcherTest {

    @Test
    fun match_singleLabel_usesSameLabelForDisplayAndCooldown() {
        val match = LocalHazardItemMatcher.match(
            itemName = "燃气灶",
            detectedScoresByLabel = mapOf("燃气灶" to 0.91f),
        )

        assertEquals("燃气灶", match?.matchedItem)
        assertEquals("燃气灶", match?.cooldownLabel)
        assertEquals(0.91f, match?.score ?: 0f, 0.0001f)
    }

    @Test
    fun match_combinedLabel_requiresAllLabels() {
        assertNull(
            LocalHazardItemMatcher.match(
                itemName = "炭炉&燃气灶",
                detectedScoresByLabel = mapOf("炭炉" to 0.88f),
            ),
        )
        assertNull(
            LocalHazardItemMatcher.match(
                itemName = "炭炉&燃气灶",
                detectedScoresByLabel = mapOf("燃气灶" to 0.92f),
            ),
        )
    }

    @Test
    fun match_combinedLabel_usesFirstLabelAsCooldownLabel() {
        val match = LocalHazardItemMatcher.match(
            itemName = " 炭炉 & 燃气灶 ",
            detectedScoresByLabel = mapOf(
                "炭炉" to 0.88f,
                "燃气灶" to 0.92f,
            ),
        )

        assertEquals("炭炉&燃气灶", match?.matchedItem)
        assertEquals("炭炉", match?.cooldownLabel)
        assertEquals(0.88f, match?.score ?: 0f, 0.0001f)
    }

    @Test
    fun splitItemLabels_ignoresBlankParts() {
        assertEquals(
            listOf("炭炉", "燃气灶"),
            LocalHazardItemMatcher.splitItemLabels(" 炭炉 &  & 燃气灶 "),
        )
    }
}
