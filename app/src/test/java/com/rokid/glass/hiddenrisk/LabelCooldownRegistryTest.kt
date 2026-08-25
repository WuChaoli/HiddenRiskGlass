package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelCooldownRegistryTest {
    private val registry = LabelCooldownRegistry(cooldownMs = 15_000L)

    @Test
    fun `marked label cools for fifteen seconds and expires at boundary`() {
        registry.mark(listOf("燃气灶"), nowElapsedMs = 1_000L)

        assertTrue(registry.isCooling("燃气灶", nowElapsedMs = 15_999L))
        assertFalse(registry.isCooling("燃气灶", nowElapsedMs = 16_000L))
    }

    @Test
    fun `mark trims deduplicates and refreshes labels independently`() {
        registry.mark(listOf(" 燃气灶 ", "燃气灶", ""), nowElapsedMs = 1_000L)
        registry.mark(listOf("热水器"), nowElapsedMs = 5_000L)

        assertEquals(listOf("热水器"), registry.coolingLabels(listOf("燃气灶", "热水器"), 16_000L))
        assertFalse(registry.isCooling("燃气灶", 16_000L))
        assertTrue(registry.isCooling("热水器", 16_000L))
    }
}
